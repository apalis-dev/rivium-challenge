use std::{
    collections::HashMap,
    error::Error,
    net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr},
    str::FromStr,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use apalis::prelude::*;
use apalis_cron::{CronScheduler, Tick};
use apalis_sqlite::{SqlitePool, queries::push_tasks};
use chrono::{DateTime, Utc};
use cron::Schedule;
use reqwest::{Client, Method, StatusCode, Url};
use serde::{Deserialize, Serialize};
use sqlx::Row;
use tokio::{
    net::lookup_host,
    task::JoinHandle,
    time::{sleep, timeout},
};

const DEFAULT_CONNECT_TIMEOUT: Duration = Duration::from_secs(5);
const DEFAULT_TOTAL_TIMEOUT: Duration = Duration::from_secs(15);
const DEFAULT_MAX_REDIRECTS: usize = 5;
const DEFAULT_MAX_RETRIES: usize = 3;

#[derive(Debug, Clone)]
pub struct MonitoringConfig {
    pub connection_timeout: Duration,
    pub total_timeout: Duration,
    pub max_redirects: usize,
    pub max_retries: usize,
}

impl Default for MonitoringConfig {
    fn default() -> Self {
        Self {
            connection_timeout: DEFAULT_CONNECT_TIMEOUT,
            total_timeout: DEFAULT_TOTAL_TIMEOUT,
            max_redirects: DEFAULT_MAX_REDIRECTS,
            max_retries: DEFAULT_MAX_RETRIES,
        }
    }
}

impl MonitoringConfig {
    pub fn from_environment() -> Self {
        let defaults = Self::default();
        Self {
            connection_timeout: env_seconds("LINKQUEUE_MONITOR_CONNECT_TIMEOUT_SECONDS")
                .map(|value| value.clamp(1, 60))
                .map(Duration::from_secs)
                .unwrap_or(defaults.connection_timeout),
            total_timeout: env_seconds("LINKQUEUE_MONITOR_TOTAL_TIMEOUT_SECONDS")
                .map(|value| value.clamp(1, 120))
                .map(Duration::from_secs)
                .unwrap_or(defaults.total_timeout),
            max_redirects: env_usize("LINKQUEUE_MONITOR_MAX_REDIRECTS")
                .map(|value| value.min(DEFAULT_MAX_REDIRECTS))
                .unwrap_or(defaults.max_redirects),
            max_retries: env_usize("LINKQUEUE_MONITOR_MAX_RETRIES")
                .map(|value| value.clamp(1, 5))
                .unwrap_or(defaults.max_retries),
        }
    }
}

fn env_seconds(name: &str) -> Option<u64> {
    std::env::var(name)
        .ok()?
        .parse::<u64>()
        .ok()
        .filter(|value| *value > 0)
}

fn env_usize(name: &str) -> Option<usize> {
    std::env::var(name)
        .ok()?
        .parse::<usize>()
        .ok()
        .filter(|value| *value > 0)
}

#[derive(Debug, Clone)]
pub struct MonitoringWorkerData {
    pub pool: SqlitePool,
    pub config: MonitoringConfig,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MonitoringJob {
    pub schedule_id: String,
    pub scheduled_occurrence: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MonitoringResult {
    pub scheduled_occurrence: i64,
    pub checked_at: i64,
    pub final_url: Option<String>,
    pub http_status: Option<u16>,
    pub response_duration_ms: Option<u64>,
    pub classification: String,
    pub error_category: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
struct MonitoringView {
    schedule_id: String,
    saved_link_id: i64,
    interval: String,
    enabled: bool,
    created_at: i64,
    next_execution_at: i64,
    last_execution_at: Option<i64>,
    latest: Option<MonitoringResult>,
    history: Vec<MonitoringResult>,
}

#[derive(Debug, Clone)]
struct CronData {
    pool: SqlitePool,
    schedule_id: String,
}

#[derive(Debug)]
struct CheckResponse {
    final_url: String,
    status: StatusCode,
    duration_ms: u64,
}

#[derive(Debug)]
enum CheckError {
    InvalidUrl,
    BlockedAddress,
    Dns,
    Timeout,
    Connection,
    Redirect,
    RedirectResponse {
        status: StatusCode,
        final_url: String,
    },
    BlockedRedirect {
        status: StatusCode,
        final_url: String,
    },
}

impl CheckError {
    fn category(&self) -> &'static str {
        match self {
            Self::InvalidUrl => "invalid_url",
            Self::BlockedAddress => "blocked_address",
            Self::Dns => "dns_error",
            Self::Timeout => "timeout",
            Self::Connection => "connection_error",
            Self::Redirect => "redirect_error",
            Self::RedirectResponse { .. } => "redirect_error",
            Self::BlockedRedirect { .. } => "blocked_address",
        }
    }

    fn classification(&self) -> &'static str {
        match self {
            Self::InvalidUrl | Self::BlockedAddress => "invalid",
            Self::RedirectResponse { .. } => "redirected",
            Self::BlockedRedirect { .. } => "invalid",
            _ => "unreachable",
        }
    }
}

pub async fn setup(pool: &SqlitePool) -> Result<(), sqlx::Error> {
    sqlx::query(
        "CREATE TABLE IF NOT EXISTS monitoring_schedules (
            id TEXT PRIMARY KEY NOT NULL,
            saved_link_id INTEGER NOT NULL UNIQUE,
            url TEXT NOT NULL,
            cron_expression TEXT NOT NULL,
            enabled INTEGER NOT NULL DEFAULT 1,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            last_execution_at INTEGER,
            next_execution_at INTEGER NOT NULL
        )",
    )
    .execute(pool)
    .await?;
    sqlx::query(
        "CREATE TABLE IF NOT EXISTS monitoring_occurrences (
            schedule_id TEXT NOT NULL,
            scheduled_occurrence INTEGER NOT NULL,
            dispatched INTEGER NOT NULL DEFAULT 0,
            state TEXT NOT NULL DEFAULT 'queued',
            created_at INTEGER NOT NULL,
            PRIMARY KEY(schedule_id, scheduled_occurrence),
            FOREIGN KEY(schedule_id) REFERENCES monitoring_schedules(id) ON DELETE CASCADE
        )",
    )
    .execute(pool)
    .await?;
    sqlx::query(
        "CREATE TABLE IF NOT EXISTS monitoring_results (
            id TEXT PRIMARY KEY NOT NULL,
            schedule_id TEXT NOT NULL,
            scheduled_occurrence INTEGER NOT NULL,
            checked_at INTEGER NOT NULL,
            final_url TEXT,
            http_status INTEGER,
            response_duration_ms INTEGER,
            classification TEXT NOT NULL,
            error_category TEXT,
            UNIQUE(schedule_id, scheduled_occurrence),
            FOREIGN KEY(schedule_id) REFERENCES monitoring_schedules(id) ON DELETE CASCADE
        )",
    )
    .execute(pool)
    .await?;
    sqlx::query(
        "CREATE INDEX IF NOT EXISTS idx_monitoring_results_schedule_checked
         ON monitoring_results(schedule_id, checked_at DESC)",
    )
    .execute(pool)
    .await?;
    sqlx::query(
        "CREATE INDEX IF NOT EXISTS idx_monitoring_occurrences_state
         ON monitoring_occurrences(schedule_id, state)",
    )
    .execute(pool)
    .await?;
    sqlx::query("PRAGMA foreign_keys = ON")
        .execute(pool)
        .await?;
    Ok(())
}

pub fn cron_for_interval(interval: &str) -> Result<&'static str, &'static str> {
    match interval {
        "hourly" => Ok("0 0 * * * *"),
        "6_hours" => Ok("0 0 */6 * * *"),
        "12_hours" => Ok("0 0 */12 * * *"),
        "daily" => Ok("0 0 0 * * *"),
        "weekly" => Ok("0 0 0 * * 1"),
        _ => Err("Unsupported monitoring interval"),
    }
}

pub async fn create_schedule(
    pool: &SqlitePool,
    saved_link_id: i64,
    url: String,
    interval: &str,
) -> Result<String, String> {
    let cron_expression = cron_for_interval(interval)?;
    let parsed_url = validate_http_url(&url).map_err(|error| error.category().to_owned())?;
    vetted_addresses(
        &parsed_url,
        false,
        MonitoringConfig::default().connection_timeout,
    )
    .await
    .map_err(|error| error.category().to_owned())?;
    let schedule = Schedule::from_str(cron_expression).map_err(|_| "Invalid interval")?;
    let now = unix_now();
    let next = schedule
        .after(&DateTime::<Utc>::from_timestamp(now, 0).ok_or("Invalid current time")?)
        .next()
        .ok_or("Could not calculate next execution")?
        .timestamp();
    let schedule_id = uuid_string();
    sqlx::query(
        "INSERT INTO monitoring_schedules
         (id, saved_link_id, url, cron_expression, enabled, created_at, updated_at, next_execution_at)
         VALUES (?, ?, ?, ?, 1, ?, ?, ?)
         ON CONFLICT(saved_link_id) DO NOTHING",
    )
    .bind(&schedule_id)
    .bind(saved_link_id)
    .bind(url)
    .bind(cron_expression)
    .bind(now)
    .bind(now)
    .bind(next)
    .execute(pool)
    .await
    .map_err(|_| "Could not save monitoring schedule".to_owned())?;
    let existing: (String,) =
        sqlx::query_as("SELECT id FROM monitoring_schedules WHERE saved_link_id = ?")
            .bind(saved_link_id)
            .fetch_one(pool)
            .await
            .map_err(|_| "Could not load monitoring schedule".to_owned())?;
    Ok(existing.0)
}

pub async fn set_enabled(
    pool: &SqlitePool,
    schedule_id: &str,
    enabled: bool,
) -> Result<(), String> {
    let mut tx = pool
        .begin()
        .await
        .map_err(|_| "Could not update monitoring schedule".to_owned())?;
    let changed =
        sqlx::query("UPDATE monitoring_schedules SET enabled = ?, updated_at = ? WHERE id = ?")
            .bind(enabled)
            .bind(unix_now())
            .bind(schedule_id)
            .execute(&mut *tx)
            .await
            .map_err(|_| "Could not update monitoring schedule".to_owned())?
            .rows_affected();
    if changed == 0 {
        return Err("Monitoring schedule not found".to_owned());
    }
    if !enabled {
        sqlx::query("UPDATE monitoring_occurrences SET state = 'cancelled' WHERE schedule_id = ? AND state = 'queued'")
            .bind(schedule_id)
            .execute(&mut *tx)
            .await
            .map_err(|_| "Could not pause monitoring schedule".to_owned())?;
    }
    tx.commit()
        .await
        .map_err(|_| "Could not update monitoring schedule".to_owned())?;
    Ok(())
}

pub async fn delete_schedule(pool: &SqlitePool, schedule_id: &str) -> Result<(), String> {
    sqlx::query("DELETE FROM monitoring_schedules WHERE id = ?")
        .bind(schedule_id)
        .execute(pool)
        .await
        .map_err(|_| "Could not delete monitoring schedule".to_owned())?;
    Ok(())
}

pub async fn delete_for_saved_link(pool: &SqlitePool, saved_link_id: i64) -> Result<(), String> {
    sqlx::query("DELETE FROM monitoring_schedules WHERE saved_link_id = ?")
        .bind(saved_link_id)
        .execute(pool)
        .await
        .map_err(|_| "Could not delete saved link monitoring".to_owned())?;
    Ok(())
}

pub async fn list_for_android(pool: &SqlitePool) -> Result<String, String> {
    let schedules = sqlx::query(
        "SELECT id, saved_link_id, cron_expression, enabled, created_at,
                next_execution_at, last_execution_at
         FROM monitoring_schedules ORDER BY created_at DESC",
    )
    .fetch_all(pool)
    .await
    .map_err(|_| "Could not load monitoring schedules".to_owned())?;
    let mut result = Vec::with_capacity(schedules.len());
    for row in schedules {
        let id: String = row.try_get("id").map_err(|_| "Invalid schedule data")?;
        let raw_history = sqlx::query(
            "SELECT scheduled_occurrence, checked_at, final_url, http_status,
                    response_duration_ms, classification, error_category
             FROM monitoring_results WHERE schedule_id = ? ORDER BY checked_at DESC LIMIT 5",
        )
        .bind(&id)
        .fetch_all(pool)
        .await
        .map_err(|_| "Could not load monitoring history")?;
        let history = raw_history
            .into_iter()
            .map(|row| result_from_row(&row))
            .collect::<Result<Vec<_>, _>>()?;
        result.push(MonitoringView {
            schedule_id: id,
            saved_link_id: row
                .try_get("saved_link_id")
                .map_err(|_| "Invalid schedule data")?,
            interval: interval_name(
                row.try_get("cron_expression")
                    .map_err(|_| "Invalid schedule data")?,
            ),
            enabled: row
                .try_get::<i64, _>("enabled")
                .map_err(|_| "Invalid schedule data")?
                != 0,
            created_at: row
                .try_get("created_at")
                .map_err(|_| "Invalid schedule data")?,
            next_execution_at: row
                .try_get("next_execution_at")
                .map_err(|_| "Invalid schedule data")?,
            last_execution_at: row
                .try_get("last_execution_at")
                .map_err(|_| "Invalid schedule data")?,
            latest: history.first().cloned(),
            history,
        });
    }
    serde_json::to_string(&result).map_err(|_| "Could not encode monitoring data".to_owned())
}

fn result_from_row(row: &sqlx::sqlite::SqliteRow) -> Result<MonitoringResult, String> {
    Ok(MonitoringResult {
        scheduled_occurrence: row
            .try_get("scheduled_occurrence")
            .map_err(|_| "Invalid result data")?,
        checked_at: row
            .try_get("checked_at")
            .map_err(|_| "Invalid result data")?,
        final_url: row
            .try_get::<Option<String>, _>("final_url")
            .map(|value| value.map(|url| redact_url(&url)))
            .map_err(|_| "Invalid result data")?,
        http_status: row
            .try_get::<Option<i64>, _>("http_status")
            .map(|value| value.map(|n| n as u16))
            .map_err(|_| "Invalid result data")?,
        response_duration_ms: row
            .try_get("response_duration_ms")
            .map_err(|_| "Invalid result data")?,
        classification: row
            .try_get("classification")
            .map_err(|_| "Invalid result data")?,
        error_category: row
            .try_get("error_category")
            .map_err(|_| "Invalid result data")?,
    })
}

pub async fn run_scheduler(pool: SqlitePool) -> Result<(), Box<dyn Error + Send + Sync>> {
    recover_due(&pool).await?;
    let mut registrations = RegistrationTasks(HashMap::new());
    loop {
        recover_due(&pool).await?;
        let rows = sqlx::query_as::<_, (String, String)>(
            "SELECT id, cron_expression FROM monitoring_schedules WHERE enabled = 1",
        )
        .fetch_all(&pool)
        .await?;
        let desired: HashMap<_, _> = rows.into_iter().collect();
        let stale: Vec<_> = registrations
            .0
            .iter()
            .filter(|(id, (expression, task))| {
                desired.get(*id) != Some(expression) || task.is_finished()
            })
            .map(|(id, _)| id.clone())
            .collect();
        for id in stale {
            if let Some((_, task)) = registrations.0.remove(&id) {
                task.abort();
            }
        }
        for (id, expression) in desired {
            if registrations.0.contains_key(&id) {
                continue;
            }
            let parsed = Schedule::from_str(&expression)?;
            let data = CronData {
                pool: pool.clone(),
                schedule_id: id.clone(),
            };
            let worker_id = id.clone();
            let task = tokio::spawn(async move {
                let backend = CronScheduler::new(parsed);
                let worker = WorkerBuilder::new(format!("monitor-cron-{worker_id}"))
                    .backend(backend)
                    .data(data)
                    .build(cron_tick);
                let _ = worker.run().await;
            });
            registrations.0.insert(id, (expression, task));
        }
        sleep(Duration::from_secs(2)).await;
    }
}

struct RegistrationTasks(HashMap<String, (String, JoinHandle<()>)>);

impl Drop for RegistrationTasks {
    fn drop(&mut self) {
        for (_, task) in self.0.values() {
            task.abort();
        }
    }
}

async fn cron_tick(_tick: Tick, data: Data<CronData>) -> Result<(), BoxDynError> {
    dispatch_due(&data.pool, &data.schedule_id).await?;
    Ok(())
}

async fn recover_due(pool: &SqlitePool) -> Result<(), sqlx::Error> {
    let ids: Vec<String> = sqlx::query_scalar(
        "SELECT id FROM monitoring_schedules WHERE enabled = 1 AND next_execution_at <= ?",
    )
    .bind(unix_now())
    .fetch_all(pool)
    .await?;
    for id in ids {
        dispatch_due(pool, &id)
            .await
            .map_err(|error| sqlx::Error::Protocol(error.to_string()))?;
    }
    Ok(())
}

async fn dispatch_due(
    pool: &SqlitePool,
    schedule_id: &str,
) -> Result<(), Box<dyn Error + Send + Sync>> {
    #[cfg(test)]
    {
        dispatch_due_with_failpoint(pool, schedule_id, None).await
    }
    #[cfg(not(test))]
    {
        dispatch_due_with_failpoint(pool, schedule_id).await
    }
}

#[cfg(test)]
#[derive(Clone, Copy, PartialEq, Eq)]
enum DispatchFailpoint {
    AfterOccurrence,
    AfterQueueInsert,
}

async fn dispatch_due_with_failpoint(
    pool: &SqlitePool,
    schedule_id: &str,
    #[cfg(test)] failpoint: Option<DispatchFailpoint>,
) -> Result<(), Box<dyn Error + Send + Sync>> {
    let now = unix_now();
    let mut tx = pool.begin().await?;
    let row = sqlx::query(
        "SELECT url, cron_expression, next_execution_at FROM monitoring_schedules
         WHERE id = ? AND enabled = 1 AND next_execution_at <= ?",
    )
    .bind(schedule_id)
    .bind(now)
    .fetch_optional(&mut *tx)
    .await?;
    let Some(row) = row else {
        tx.rollback().await?;
        return Ok(());
    };
    let occurrence: i64 = row.try_get("next_execution_at")?;
    let expression: String = row.try_get("cron_expression")?;
    let active: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM monitoring_occurrences WHERE schedule_id = ? AND state IN ('queued', 'running')",
    )
    .bind(schedule_id)
    .fetch_one(&mut *tx)
    .await?;
    let schedule = Schedule::from_str(&expression)?;
    let next = schedule
        .after(&DateTime::<Utc>::from_timestamp(now, 0).ok_or("Invalid current time")?)
        .next()
        .ok_or("Could not calculate next execution")?
        .timestamp();
    if active == 0 {
        let inserted = sqlx::query(
            "INSERT OR IGNORE INTO monitoring_occurrences
             (schedule_id, scheduled_occurrence, dispatched, state, created_at)
             VALUES (?, ?, 0, 'queued', ?)",
        )
        .bind(schedule_id)
        .bind(occurrence)
        .bind(now)
        .execute(&mut *tx)
        .await?
        .rows_affected();
        if inserted == 1 {
            #[cfg(test)]
            if failpoint == Some(DispatchFailpoint::AfterOccurrence) {
                return Err(
                    std::io::Error::other("simulated crash after occurrence insert").into(),
                );
            }
            let job = MonitoringJob {
                schedule_id: schedule_id.to_owned(),
                scheduled_occurrence: occurrence,
            };
            let encoded = apalis_sqlite::JsonCodec::<Vec<u8>>::default().encode(&job)?;
            let idem_key = occurrence_key(schedule_id, occurrence);
            let task = TaskBuilder::new(encoded)
                .idempotency_key(&idem_key)
                .task_id(TaskId::from_string(idem_key.clone()))
                .build();
            push_tasks(&mut *tx, std::any::type_name::<MonitoringJob>(), &[task]).await?;
            #[cfg(test)]
            if failpoint == Some(DispatchFailpoint::AfterQueueInsert) {
                return Err(std::io::Error::other("simulated crash after queue insert").into());
            }
            sqlx::query(
                "UPDATE monitoring_occurrences SET dispatched = 1 WHERE schedule_id = ? AND scheduled_occurrence = ?",
            )
            .bind(schedule_id)
            .bind(occurrence)
            .execute(&mut *tx)
            .await?;
        }
    }
    sqlx::query("UPDATE monitoring_schedules SET next_execution_at = ?, updated_at = ? WHERE id = ? AND enabled = 1")
        .bind(next)
        .bind(now)
        .bind(schedule_id)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    Ok(())
}

pub async fn process_job(
    job: MonitoringJob,
    context: Data<MonitoringWorkerData>,
) -> Result<(), BoxDynError> {
    let pool = &context.pool;
    let Some(url): Option<String> = sqlx::query_scalar(
        "SELECT s.url FROM monitoring_schedules s
         JOIN monitoring_occurrences o ON o.schedule_id = s.id
         WHERE s.id = ? AND s.enabled = 1 AND o.scheduled_occurrence = ? AND o.dispatched = 1",
    )
    .bind(&job.schedule_id)
    .bind(job.scheduled_occurrence)
    .fetch_optional(&*pool)
    .await?
    else {
        let _ = sqlx::query("UPDATE monitoring_occurrences SET state = 'cancelled' WHERE schedule_id = ? AND scheduled_occurrence = ? AND state = 'queued'")
            .bind(&job.schedule_id)
            .bind(job.scheduled_occurrence)
            .execute(&*pool)
            .await;
        return Ok(());
    };

    let claimed = claim_occurrence(&pool, &job).await?;
    if !claimed {
        return Ok(());
    }

    let checked_at = unix_now();
    let check_started = Instant::now();
    let result = match check_with_retries(&url, &context.config).await {
        Ok(response) => MonitoringResult {
            scheduled_occurrence: job.scheduled_occurrence,
            checked_at,
            final_url: Some(response.final_url),
            http_status: Some(response.status.as_u16()),
            response_duration_ms: Some(response.duration_ms),
            classification: if response.status.is_success() {
                "available"
            } else if response.status.is_redirection() {
                "redirected"
            } else {
                "http_error"
            }
            .to_owned(),
            error_category: None,
        },
        Err(error) => {
            let (final_url, http_status) = match &error {
                CheckError::RedirectResponse { status, final_url }
                | CheckError::BlockedRedirect { status, final_url } => {
                    (Some(final_url.clone()), Some(status.as_u16()))
                }
                _ => (
                    validate_http_url(&url)
                        .ok()
                        .map(|parsed| parsed.to_string()),
                    None,
                ),
            };
            MonitoringResult {
                scheduled_occurrence: job.scheduled_occurrence,
                checked_at,
                final_url,
                http_status,
                response_duration_ms: Some(
                    check_started.elapsed().as_millis().min(u64::MAX as u128) as u64,
                ),
                classification: error.classification().to_owned(),
                error_category: Some(error.category().to_owned()),
            }
        }
    };
    persist_result(&pool, &job, result).await?;
    Ok(())
}

async fn claim_occurrence(pool: &SqlitePool, job: &MonitoringJob) -> Result<bool, sqlx::Error> {
    let claimed = sqlx::query(
        "UPDATE monitoring_occurrences SET state = 'running'
         WHERE schedule_id = ? AND scheduled_occurrence = ? AND state IN ('queued', 'running')
         AND EXISTS (SELECT 1 FROM monitoring_schedules WHERE id = ? AND enabled = 1)
         AND NOT EXISTS (
           SELECT 1 FROM monitoring_occurrences WHERE schedule_id = ? AND state = 'running'
             AND scheduled_occurrence <> ?
         )",
    )
    .bind(&job.schedule_id)
    .bind(job.scheduled_occurrence)
    .bind(&job.schedule_id)
    .bind(&job.schedule_id)
    .bind(job.scheduled_occurrence)
    .execute(pool)
    .await?
    .rows_affected();
    Ok(claimed == 1)
}

async fn persist_result(
    pool: &SqlitePool,
    job: &MonitoringJob,
    result: MonitoringResult,
) -> Result<(), sqlx::Error> {
    let mut tx = pool.begin().await?;
    let exists: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM monitoring_schedules WHERE id = ?")
        .bind(&job.schedule_id)
        .fetch_one(&mut *tx)
        .await?;
    if exists == 0 {
        tx.rollback().await?;
        return Ok(());
    }
    sqlx::query(
        "INSERT OR IGNORE INTO monitoring_results
         (id, schedule_id, scheduled_occurrence, checked_at, final_url, http_status,
          response_duration_ms, classification, error_category)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(uuid_string())
    .bind(&job.schedule_id)
    .bind(result.scheduled_occurrence)
    .bind(result.checked_at)
    .bind(result.final_url)
    .bind(result.http_status.map(i64::from))
    .bind(result.response_duration_ms.map(|n| n as i64))
    .bind(result.classification)
    .bind(result.error_category)
    .execute(&mut *tx)
    .await?;
    sqlx::query(
        "UPDATE monitoring_schedules SET last_execution_at = ?, updated_at = ? WHERE id = ?",
    )
    .bind(result.checked_at)
    .bind(result.checked_at)
    .bind(&job.schedule_id)
    .execute(&mut *tx)
    .await?;
    sqlx::query("UPDATE monitoring_occurrences SET state = 'completed' WHERE schedule_id = ? AND scheduled_occurrence = ?")
        .bind(&job.schedule_id)
        .bind(job.scheduled_occurrence)
        .execute(&mut *tx)
        .await?;
    tx.commit().await
}

async fn check_with_retries(
    raw_url: &str,
    config: &MonitoringConfig,
) -> Result<CheckResponse, CheckError> {
    let started = Instant::now();
    let mut last_error = CheckError::Connection;
    for attempt in 0..config.max_retries.max(1) {
        let remaining = config.total_timeout.saturating_sub(started.elapsed());
        if remaining.is_zero() {
            return Err(CheckError::Timeout);
        }
        match timeout(remaining, check_once(raw_url, remaining, config)).await {
            Ok(Ok(response)) => return Ok(response),
            Ok(Err(error)) => {
                let transient = matches!(
                    error,
                    CheckError::Dns | CheckError::Timeout | CheckError::Connection
                );
                last_error = error;
                if !transient || attempt + 1 == config.max_retries.max(1) {
                    break;
                }
            }
            Err(_) => return Err(CheckError::Timeout),
        }
        sleep(Duration::from_millis(
            250u64.saturating_mul(2u64.pow(attempt as u32)),
        ))
        .await;
    }
    Err(last_error)
}

async fn check_once(
    raw_url: &str,
    total_remaining: Duration,
    config: &MonitoringConfig,
) -> Result<CheckResponse, CheckError> {
    check_once_inner(raw_url, total_remaining, false, false, config).await
}

async fn check_once_inner(
    raw_url: &str,
    total_remaining: Duration,
    allow_private_for_tests: bool,
    enforce_redirect_security: bool,
    config: &MonitoringConfig,
) -> Result<CheckResponse, CheckError> {
    let mut current = validate_http_url(raw_url)?;
    let start = Instant::now();
    let mut pinned_addresses = None;
    for redirect_count in 0..=config.max_redirects.min(DEFAULT_MAX_REDIRECTS) {
        let addresses = match pinned_addresses.take() {
            Some(addresses) => addresses,
            None => {
                vetted_addresses(
                    &current,
                    allow_private_for_tests && (!enforce_redirect_security || redirect_count == 0),
                    config.connection_timeout,
                )
                .await?
            }
        };
        let host = current.host_str().ok_or(CheckError::InvalidUrl)?.to_owned();
        let client = Client::builder()
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .connect_timeout(config.connection_timeout.min(total_remaining))
            .timeout(total_remaining.min(config.total_timeout))
            .resolve_to_addrs(&host, &addresses)
            .build()
            .map_err(|_| CheckError::Connection)?;
        let mut response = client
            .request(Method::HEAD, current.clone())
            .send()
            .await
            .map_err(map_reqwest_error)?;
        if response.status() == StatusCode::METHOD_NOT_ALLOWED
            || response.status() == StatusCode::NOT_IMPLEMENTED
        {
            drop(response);
            response = client
                .request(Method::GET, current.clone())
                .send()
                .await
                .map_err(map_reqwest_error)?;
        }
        let status = response.status();
        if status.is_redirection() {
            if redirect_count == config.max_redirects.min(DEFAULT_MAX_REDIRECTS) {
                return Err(CheckError::RedirectResponse {
                    status,
                    final_url: current.to_string(),
                });
            }
            let location = response
                .headers()
                .get(reqwest::header::LOCATION)
                .and_then(|value| value.to_str().ok())
                .ok_or_else(|| CheckError::RedirectResponse {
                    status,
                    final_url: current.to_string(),
                })?;
            let next = current
                .join(location)
                .map_err(|_| CheckError::RedirectResponse {
                    status,
                    final_url: current.to_string(),
                })?;
            let next =
                validate_http_url(next.as_str()).map_err(|_| CheckError::RedirectResponse {
                    status,
                    final_url: current.to_string(),
                })?;
            let next_addresses = match vetted_addresses(
                &next,
                allow_private_for_tests && !enforce_redirect_security,
                config.connection_timeout,
            )
            .await
            {
                Ok(addresses) => addresses,
                Err(CheckError::BlockedAddress) => {
                    return Err(CheckError::BlockedRedirect {
                        status,
                        final_url: current.to_string(),
                    });
                }
                Err(error) => return Err(error),
            };
            drop(response);
            current = next;
            pinned_addresses = Some(next_addresses);
            continue;
        }
        drop(response);
        return Ok(CheckResponse {
            final_url: current.to_string(),
            status,
            duration_ms: start.elapsed().as_millis().min(u64::MAX as u128) as u64,
        });
    }
    Err(CheckError::Redirect)
}

fn validate_http_url(raw: &str) -> Result<Url, CheckError> {
    let url = Url::parse(raw).map_err(|_| CheckError::InvalidUrl)?;
    if !matches!(url.scheme(), "http" | "https")
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
    {
        return Err(CheckError::InvalidUrl);
    }
    Ok(url)
}

async fn vetted_addresses(
    url: &Url,
    allow_private_for_tests: bool,
    connection_timeout: Duration,
) -> Result<Vec<SocketAddr>, CheckError> {
    let host = url.host_str().ok_or(CheckError::InvalidUrl)?;
    let port = url.port_or_known_default().ok_or(CheckError::InvalidUrl)?;
    let resolved: Vec<SocketAddr> = if let Ok(ip) = host.parse::<IpAddr>() {
        vec![SocketAddr::new(ip, port)]
    } else {
        timeout(connection_timeout, lookup_host((host, port)))
            .await
            .map_err(|_| CheckError::Timeout)?
            .map_err(|_| CheckError::Dns)?
            .collect()
    };
    if resolved.is_empty() {
        return Err(CheckError::Dns);
    }
    if !allow_private_for_tests && resolved.iter().any(|address| !is_public_ip(address.ip())) {
        return Err(CheckError::BlockedAddress);
    }
    Ok(resolved)
}

fn is_public_ip(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(ip) => public_v4(ip),
        IpAddr::V6(ip) => public_v6(ip),
    }
}

fn public_v4(ip: Ipv4Addr) -> bool {
    let [a, b, c, _] = ip.octets();
    !(a == 0
        || a == 10
        || a == 127
        || a >= 224
        || (a == 100 && (64..=127).contains(&b))
        || (a == 169 && b == 254)
        || (a == 172 && (16..=31).contains(&b))
        || (a == 192 && (b == 0 || b == 168))
        || (a == 192 && b == 88 && c == 99)
        || (a == 198 && (b == 18 || b == 19 || b == 51))
        || (a == 203 && b == 0 && c == 113))
}

fn public_v6(ip: Ipv6Addr) -> bool {
    let segments = ip.segments();
    if ip.is_unspecified() || ip.is_loopback() || ip.is_multicast() || ip.to_ipv4_mapped().is_some()
    {
        return false;
    }
    // Global unicast 2000::/3 only; exclude documentation and special-purpose ranges.
    (segments[0] & 0xe000) == 0x2000
        && segments[0] != 0x2002
        && segments[0] != 0x3fff
        && !(segments[0] == 0x2001 && (segments[1] == 0x0db8 || segments[1] & 0xfe00 == 0))
}

fn map_reqwest_error(error: reqwest::Error) -> CheckError {
    if error.is_connect() {
        CheckError::Connection
    } else if error.is_timeout() {
        CheckError::Timeout
    } else {
        CheckError::Connection
    }
}

fn redact_url(raw: &str) -> String {
    match Url::parse(raw) {
        Ok(mut url) => {
            url.set_query(None);
            url.set_fragment(None);
            url.to_string()
        }
        Err(_) => "(URL unavailable)".to_owned(),
    }
}

fn interval_name(expression: String) -> String {
    match expression.as_str() {
        "0 0 * * * *" => "hourly".to_owned(),
        "0 0 */6 * * *" => "6_hours".to_owned(),
        "0 0 */12 * * *" => "12_hours".to_owned(),
        "0 0 0 * * *" => "daily".to_owned(),
        "0 0 0 * * 1" => "weekly".to_owned(),
        _ => "custom".to_owned(),
    }
}

fn occurrence_key(schedule_id: &str, timestamp: i64) -> String {
    format!("monitor:{schedule_id}:{timestamp}")
}

fn unix_now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64
}

fn uuid_string() -> String {
    crate::jobs::new_job_id(&format!(
        "monitor:{}",
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos()
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    use apalis_sqlite::SqliteStorage;
    use sqlx::sqlite::SqlitePoolOptions;
    use tokio::{
        io::{AsyncReadExt, AsyncWriteExt},
        net::TcpListener,
    };

    async fn test_pool() -> SqlitePool {
        let pool = SqlitePoolOptions::new()
            .max_connections(1)
            .connect("sqlite::memory:")
            .await
            .unwrap();
        SqliteStorage::setup(&pool).await.unwrap();
        setup(&pool).await.unwrap();
        pool
    }

    async fn insert_schedule(pool: &SqlitePool, id: &str, next: i64) {
        sqlx::query(
            "INSERT INTO monitoring_schedules
             (id, saved_link_id, url, cron_expression, enabled, created_at, updated_at, next_execution_at)
             VALUES (?, ?, 'https://example.com/path?secret=value', '0 0 * * * *', 1, ?, ?, ?)",
        )
        .bind(id)
        .bind(7_i64)
        .bind(unix_now())
        .bind(unix_now())
        .bind(next)
        .execute(pool)
        .await
        .unwrap();
    }

    async fn count(pool: &SqlitePool, query: &'static str) -> i64 {
        sqlx::query_scalar(query).fetch_one(pool).await.unwrap()
    }

    fn test_config(total_timeout: Duration) -> MonitoringConfig {
        MonitoringConfig {
            total_timeout,
            ..MonitoringConfig::default()
        }
    }

    async fn mock_server(responses: Vec<String>, delay: Duration) -> (String, JoinHandle<()>) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let task = tokio::spawn(async move {
            for response in responses {
                let Ok((mut socket, _)) = listener.accept().await else {
                    break;
                };
                let mut request = [0_u8; 2048];
                let _ = socket.read(&mut request).await;
                sleep(delay).await;
                let _ = socket.write_all(response.as_bytes()).await;
            }
        });
        (format!("http://{address}/start"), task)
    }

    #[test]
    fn presets_are_supported_and_stable() {
        assert_eq!(cron_for_interval("hourly").unwrap(), "0 0 * * * *");
        assert_eq!(cron_for_interval("weekly").unwrap(), "0 0 0 * * 1");
        assert!(cron_for_interval("bogus").is_err());
    }

    #[test]
    fn http_limits_have_expected_configurable_defaults() {
        let config = MonitoringConfig::default();
        assert_eq!(config.connection_timeout, Duration::from_secs(5));
        assert_eq!(config.total_timeout, Duration::from_secs(15));
        assert_eq!(config.max_redirects, 5);
        assert_eq!(config.max_retries, 3);
    }

    #[test]
    fn occurrence_identity_is_deterministic() {
        assert_eq!(
            occurrence_key("schedule-a", 123),
            occurrence_key("schedule-a", 123)
        );
        assert_ne!(
            occurrence_key("schedule-a", 123),
            occurrence_key("schedule-a", 124)
        );
    }

    #[test]
    fn rejects_non_public_addresses() {
        for address in [
            "127.0.0.1",
            "10.0.0.1",
            "172.16.1.1",
            "192.168.1.2",
            "169.254.169.254",
            "224.0.0.1",
            "::1",
            "fc00::1",
            "fe80::1",
            "2001:db8::1",
            "2002:0808:0808::1",
            "3fff::1",
        ] {
            assert!(
                !is_public_ip(address.parse().unwrap()),
                "{address} should be rejected"
            );
        }
        assert!(is_public_ip("8.8.8.8".parse().unwrap()));
        assert!(is_public_ip("2606:4700:4700::1111".parse().unwrap()));
    }

    #[test]
    fn validates_only_http_schemes_and_redacts_query() {
        assert!(validate_http_url("file:///etc/passwd").is_err());
        assert_eq!(
            redact_url("https://example.com/path?token=secret#frag"),
            "https://example.com/path"
        );
    }

    #[tokio::test]
    async fn dispatch_is_atomic_across_occurrence_and_queue_insert() {
        let pool = test_pool().await;
        insert_schedule(&pool, "atomic", unix_now() - 60).await;
        assert!(
            dispatch_due_with_failpoint(&pool, "atomic", Some(DispatchFailpoint::AfterOccurrence))
                .await
                .is_err()
        );
        assert_eq!(
            count(&pool, "SELECT COUNT(*) FROM monitoring_occurrences").await,
            0
        );
        assert_eq!(count(&pool, "SELECT COUNT(*) FROM Jobs").await, 0);

        assert!(
            dispatch_due_with_failpoint(&pool, "atomic", Some(DispatchFailpoint::AfterQueueInsert))
                .await
                .is_err()
        );
        assert_eq!(
            count(&pool, "SELECT COUNT(*) FROM monitoring_occurrences").await,
            0
        );
        assert_eq!(count(&pool, "SELECT COUNT(*) FROM Jobs").await, 0);

        dispatch_due(&pool, "atomic").await.unwrap();
        assert_eq!(
            count(
                &pool,
                "SELECT COUNT(*) FROM monitoring_occurrences WHERE dispatched = 1"
            )
            .await,
            1
        );
        assert_eq!(count(&pool, "SELECT COUNT(*) FROM Jobs").await, 1);
    }

    #[tokio::test]
    async fn repeated_recovery_creates_one_deterministic_catch_up_job() {
        let pool = test_pool().await;
        insert_schedule(&pool, "catch-up", unix_now() - 3600).await;
        let original_occurrence: i64 = sqlx::query_scalar(
            "SELECT next_execution_at FROM monitoring_schedules WHERE id = 'catch-up'",
        )
        .fetch_one(&pool)
        .await
        .unwrap();
        recover_due(&pool).await.unwrap();
        recover_due(&pool).await.unwrap();
        let stored_occurrence: i64 = sqlx::query_scalar("SELECT scheduled_occurrence FROM monitoring_occurrences WHERE schedule_id = 'catch-up'")
            .fetch_one(&pool).await.unwrap();
        assert_eq!(stored_occurrence, original_occurrence);
        assert_eq!(count(&pool, "SELECT COUNT(*) FROM Jobs").await, 1);
        assert_eq!(
            count(
                &pool,
                "SELECT COUNT(*) FROM monitoring_occurrences WHERE schedule_id = 'catch-up'"
            )
            .await,
            1
        );
    }

    #[tokio::test]
    async fn pause_cancels_queued_occurrence_and_resume_does_not_duplicate_rows() {
        let pool = test_pool().await;
        insert_schedule(&pool, "pause", unix_now() - 60).await;
        dispatch_due(&pool, "pause").await.unwrap();
        set_enabled(&pool, "pause", false).await.unwrap();
        assert_eq!(
            count(
                &pool,
                "SELECT COUNT(*) FROM monitoring_occurrences WHERE state = 'cancelled'"
            )
            .await,
            1
        );
        set_enabled(&pool, "pause", true).await.unwrap();
        set_enabled(&pool, "pause", true).await.unwrap();
        assert_eq!(
            count(
                &pool,
                "SELECT COUNT(*) FROM monitoring_schedules WHERE id = 'pause'"
            )
            .await,
            1
        );
    }

    #[tokio::test]
    async fn schedule_creation_rejects_private_network_targets() {
        let pool = test_pool().await;
        let error = create_schedule(&pool, 1, "http://127.0.0.1/".to_owned(), "hourly")
            .await
            .unwrap_err();
        assert_eq!(error, "blocked_address");
        assert_eq!(
            count(&pool, "SELECT COUNT(*) FROM monitoring_schedules").await,
            0
        );
    }

    #[tokio::test]
    async fn running_occurrence_can_be_reclaimed_after_restart_without_overlap() {
        let pool = test_pool().await;
        insert_schedule(&pool, "restart", unix_now() + 3600).await;
        sqlx::query("INSERT INTO monitoring_occurrences (schedule_id, scheduled_occurrence, dispatched, state, created_at) VALUES ('restart', 100, 1, 'running', ?)")
            .bind(unix_now()).execute(&pool).await.unwrap();
        let same = MonitoringJob {
            schedule_id: "restart".to_owned(),
            scheduled_occurrence: 100,
        };
        assert!(claim_occurrence(&pool, &same).await.unwrap());
        sqlx::query("INSERT INTO monitoring_occurrences (schedule_id, scheduled_occurrence, dispatched, state, created_at) VALUES ('restart', 200, 1, 'queued', ?)")
            .bind(unix_now()).execute(&pool).await.unwrap();
        let overlapping = MonitoringJob {
            schedule_id: "restart".to_owned(),
            scheduled_occurrence: 200,
        };
        assert!(!claim_occurrence(&pool, &overlapping).await.unwrap());
    }

    #[tokio::test]
    async fn deleted_schedule_during_check_cannot_persist_result() {
        let pool = test_pool().await;
        insert_schedule(&pool, "delete-race", unix_now() + 3600).await;
        sqlx::query("INSERT INTO monitoring_occurrences (schedule_id, scheduled_occurrence, dispatched, state, created_at) VALUES ('delete-race', 123, 1, 'running', ?)")
            .bind(unix_now()).execute(&pool).await.unwrap();
        sqlx::query("DELETE FROM monitoring_schedules WHERE id = 'delete-race'")
            .execute(&pool)
            .await
            .unwrap();
        let job = MonitoringJob {
            schedule_id: "delete-race".to_owned(),
            scheduled_occurrence: 123,
        };
        let result = MonitoringResult {
            scheduled_occurrence: 123,
            checked_at: unix_now(),
            final_url: Some("https://example.com/".to_owned()),
            http_status: Some(200),
            response_duration_ms: Some(10),
            classification: "available".to_owned(),
            error_category: None,
        };
        persist_result(&pool, &job, result).await.unwrap();
        assert_eq!(
            count(&pool, "SELECT COUNT(*) FROM monitoring_results").await,
            0
        );
    }

    #[tokio::test]
    async fn deleting_schedule_cascades_occurrences_and_history() {
        let pool = test_pool().await;
        insert_schedule(&pool, "cascade", unix_now() + 3600).await;
        sqlx::query("INSERT INTO monitoring_occurrences (schedule_id, scheduled_occurrence, dispatched, state, created_at) VALUES ('cascade', 123, 1, 'completed', ?)")
            .bind(unix_now()).execute(&pool).await.unwrap();
        sqlx::query("INSERT INTO monitoring_results (id, schedule_id, scheduled_occurrence, checked_at, classification) VALUES ('r1', 'cascade', 123, ?, 'available')")
            .bind(unix_now()).execute(&pool).await.unwrap();
        delete_schedule(&pool, "cascade").await.unwrap();
        assert_eq!(
            count(&pool, "SELECT COUNT(*) FROM monitoring_occurrences").await,
            0
        );
        assert_eq!(
            count(&pool, "SELECT COUNT(*) FROM monitoring_results").await,
            0
        );
    }

    #[tokio::test]
    async fn http_status_redirect_connection_and_timeout_are_classified_without_body_reads() {
        for (status, expected) in [
            (404, StatusCode::NOT_FOUND),
            (500, StatusCode::INTERNAL_SERVER_ERROR),
        ] {
            let (url, server) = mock_server(vec![format!("HTTP/1.1 {status} Result\r\nContent-Length: 9\r\nConnection: close\r\n\r\nsecret!!")], Duration::ZERO).await;
            let response = check_once_inner(
                &url,
                Duration::from_secs(2),
                true,
                false,
                &test_config(Duration::from_secs(2)),
            )
            .await
            .unwrap();
            assert_eq!(response.status, expected);
            server.await.unwrap();
        }

        let (url, server) = mock_server(
            vec![
                "HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .to_owned(),
                "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".to_owned(),
            ],
            Duration::ZERO,
        )
        .await;
        let response = check_once_inner(
            &url,
            Duration::from_secs(2),
            true,
            false,
            &test_config(Duration::from_secs(2)),
        )
        .await
        .unwrap();
        assert_eq!(response.status, StatusCode::OK);
        server.await.unwrap();

        let (url, server) = mock_server(vec![
            "HTTP/1.1 302 Found\r\nLocation: /final?secret=value\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".to_owned(),
            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".to_owned(),
        ], Duration::ZERO).await;
        let response = check_once_inner(
            &url,
            Duration::from_secs(2),
            true,
            false,
            &test_config(Duration::from_secs(2)),
        )
        .await
        .unwrap();
        assert_eq!(response.status, StatusCode::OK);
        assert!(response.final_url.contains("/final"));
        server.await.unwrap();

        let (url, server) = mock_server(
            vec!["HTTP/1.1 302 Found\r\nLocation: /later\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".to_owned()],
            Duration::ZERO,
        )
        .await;
        let mut no_redirects = test_config(Duration::from_secs(2));
        no_redirects.max_redirects = 0;
        let failure = check_once_inner(&url, Duration::from_secs(2), true, false, &no_redirects)
            .await
            .unwrap_err();
        assert!(matches!(
            failure,
            CheckError::RedirectResponse {
                status: StatusCode::FOUND,
                ..
            }
        ));
        server.await.unwrap();

        let target = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let target_address = target.local_addr().unwrap();
        let (url, server) = mock_server(
            vec![format!(
                "HTTP/1.1 302 Found\r\nLocation: http://{target_address}/metadata?token=secret\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            )],
            Duration::ZERO,
        )
        .await;
        let failure = check_once_inner(
            &url,
            Duration::from_secs(2),
            true,
            true,
            &test_config(Duration::from_secs(2)),
        )
        .await
        .unwrap_err();
        assert!(matches!(
            failure,
            CheckError::BlockedRedirect {
                status: StatusCode::FOUND,
                ..
            }
        ));
        assert!(
            timeout(Duration::from_millis(100), target.accept())
                .await
                .is_err()
        );
        server.await.unwrap();

        let (url, server) = mock_server(
            vec!["HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".to_owned()],
            Duration::from_millis(250),
        )
        .await;
        assert!(matches!(
            check_once_inner(
                &url,
                Duration::from_millis(40),
                true,
                false,
                &test_config(Duration::from_millis(40))
            )
            .await,
            Err(CheckError::Timeout)
        ));
        server.abort();
    }

    #[tokio::test]
    async fn invalid_url_and_connection_failure_are_handled() {
        assert!(matches!(
            check_once_inner(
                "file:///tmp/x",
                Duration::from_secs(1),
                true,
                false,
                &test_config(Duration::from_secs(1))
            )
            .await,
            Err(CheckError::InvalidUrl)
        ));
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let reset = tokio::spawn(async move {
            if let Ok((socket, _)) = listener.accept().await {
                drop(socket);
            }
        });
        let url = format!("http://{address}/");
        let failure = check_once_inner(
            &url,
            Duration::from_secs(1),
            true,
            false,
            &test_config(Duration::from_secs(1)),
        )
        .await
        .unwrap_err();
        assert!(
            matches!(failure, CheckError::Connection),
            "unexpected connection failure category: {failure:?}"
        );
        reset.await.unwrap();
    }
}
