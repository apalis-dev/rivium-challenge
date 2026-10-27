//! Android entry points for `com.taskping.app.TaskPingBridge`.
//!
//! `rivium_jni::export!` registers the five Rivium lifecycle methods (start/stop/status/...).
//! The TaskPing-specific methods talk to the same SQLite queue the Rivium-managed worker polls:
//! - `nativeRunDemoJob`: queue an Add job and block until its result (quick task).
//! - `nativeSubmitTable`: queue a long Table job and return its id immediately.
//! - `nativeCheck`: read a job's state by id; works after the app was closed and reopened.

use crate::{DemoJob, TaskPingApp, check, submit, submit_and_wait};
use apalis::prelude::BoxDynError;
use jni::{
    Env, EnvUnowned,
    errors::ThrowRuntimeExAndDefault,
    objects::{JClass, JString},
    sys::jlong,
};
use std::{future::Future, path::PathBuf};

rivium_jni::export!(class = "com/taskping/app/TaskPingBridge", app = TaskPingApp);

/// Run one client call on a short-lived runtime; errors become "error: ..." text for Kotlin.
fn block_on_text<'local, T: ToString>(
    env: &mut Env<'local>,
    call: impl Future<Output = Result<T, BoxDynError>>,
) -> jni::errors::Result<JString<'local>> {
    let outcome = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(BoxDynError::from)
        .and_then(|rt| rt.block_on(call));
    env.new_string(match outcome {
        Ok(value) => value.to_string(),
        Err(e) => format!("error: {e}"),
    })
}

/// Blocking: call off the UI thread. Returns the sum as text, or "error: ..." on failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_taskping_app_TaskPingBridge_nativeRunDemoJob<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    root: JString<'caller>,
    a: jlong,
    b: jlong,
) -> JString<'caller> {
    unowned_env
        .with_env(|env| -> jni::errors::Result<_> {
            let root = PathBuf::from(root.try_to_string(env)?);
            block_on_text(env, submit_and_wait(&root, DemoJob::Add { a, b }))
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Returns the new job id, or "error: ..." on failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_taskping_app_TaskPingBridge_nativeSubmitTable<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    root: JString<'caller>,
    base: jlong,
    upto: jlong,
) -> JString<'caller> {
    unowned_env
        .with_env(|env| -> jni::errors::Result<_> {
            let root = PathBuf::from(root.try_to_string(env)?);
            block_on_text(env, submit(&root, DemoJob::Table { base, upto }))
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Returns `done:<result>`, `failed:<error>`, `running:<step>/<total>`, `pending` or "error: ...".
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_taskping_app_TaskPingBridge_nativeCheck<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    root: JString<'caller>,
    id: JString<'caller>,
) -> JString<'caller> {
    unowned_env
        .with_env(|env| -> jni::errors::Result<_> {
            let root = PathBuf::from(root.try_to_string(env)?);
            let id = id.try_to_string(env)?;
            block_on_text(env, check(&root, &id))
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}
