use crate::job::ProcessImageJob;
use crate::storage::{enqueue_job, get_completed_results, init_db};
use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use std::path::PathBuf;
use std::time::{SystemTime, UNIX_EPOCH};

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_pocketshrink_app_Bridge_nativeEnqueue(
    mut env: JNIEnv,
    _class: JClass,
    root_dir: JString,
    input_path: JString,
    max_width: jint,
) -> jstring {
    let root_str: String = match env.get_string(&root_dir) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("ERR_INVALID_ROOT").unwrap().into_raw(),
    };
    let input_str: String = match env.get_string(&input_path) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("ERR_INVALID_PATH").unwrap().into_raw(),
    };

    let rt = match tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
    {
        Ok(r) => r,
        Err(_) => return env.new_string("ERR_RUNTIME").unwrap().into_raw(),
    };

    let res = rt.block_on(async move {
        let root = PathBuf::from(&root_str);
        let db_path = root.join("pocket_jobs.db");
        let output_dir = root.join("processed");
        let _ = tokio::fs::create_dir_all(&output_dir).await;

        let pool = init_db(&db_path).await?;
        let job_id = format!(
            "job_{}",
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis()
        );
        let job = ProcessImageJob {
            id: job_id.clone(),
            input_path: input_str,
            output_dir: output_dir.to_string_lossy().to_string(),
            max_dimension: if max_width > 0 { max_width as u32 } else { 1280 },
        };

        enqueue_job(&pool, job).await?;
        Ok::<String, Box<dyn std::error::Error + Send + Sync>>(job_id)
    });

    match res {
        Ok(id) => env.new_string(id).unwrap().into_raw(),
        Err(e) => env
            .new_string(format!("ERR: {}", e))
            .unwrap()
            .into_raw(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_pocketshrink_app_Bridge_nativeGetCompletedJobs(
    mut env: JNIEnv,
    _class: JClass,
    root_dir: JString,
) -> jstring {
    let root_str: String = match env.get_string(&root_dir) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("[]").unwrap().into_raw(),
    };

    let rt = match tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
    {
        Ok(r) => r,
        Err(_) => return env.new_string("[]").unwrap().into_raw(),
    };

    let json_res = rt.block_on(async move {
        let root = PathBuf::from(&root_str);
        let db_path = root.join("pocket_jobs.db");
        if !db_path.exists() {
            return Ok("[]".to_string());
        }
        let pool = init_db(&db_path).await?;
        let results = get_completed_results(&pool).await?;
        let json = serde_json::to_string(&results)?;
        Ok::<String, Box<dyn std::error::Error + Send + Sync>>(json)
    });

    match json_res {
        Ok(json) => env.new_string(json).unwrap().into_raw(),
        Err(_) => env.new_string("[]").unwrap().into_raw(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_pocketshrink_app_Bridge_nativeDiagnoseHost(
    mut env: JNIEnv,
    _class: JClass,
    root_dir: JString,
) -> jstring {
    let root_str: String = match env.get_string(&root_dir) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("ERR_INVALID_ROOT").unwrap().into_raw(),
    };
    let root = PathBuf::from(&root_str);
    let mut diag = Vec::new();
    diag.push(format!("root: {}", root.display()));
    diag.push(format!("root_exists: {}", root.exists()));
    let log_dir = root.join("logs").join("pocket-shrink");
    let create_res = std::fs::create_dir_all(&log_dir);
    diag.push(format!("create_log_dir: {:?}", create_res));
    let canon_res = log_dir.canonicalize();
    diag.push(format!("canonicalize: {:?}", canon_res));
    if let Ok(canon) = &canon_res {
        let lock_path = canon.join(".rivium.lock");
        let open_res = std::fs::OpenOptions::new()
            .create(true)
            .truncate(false)
            .write(true)
            .open(&lock_path);
        diag.push(format!("open_lock: {:?}", open_res));
    }

    let settings = rivium_host::log::Settings::for_app("pocket-shrink");
    let log_res = rivium_host::log::Logger::new("pocket-shrink", &root, settings, false, Vec::new());
    match log_res {
        Ok(_) => diag.push("Logger::new: OK".to_string()),
        Err(e) => diag.push(format!("Logger::new: ERR: {:?}", e)),
    }

    env.new_string(diag.join(" | ")).unwrap().into_raw()
}
