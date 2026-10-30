use crate::{db, extract, shared, Shared};
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use serde_json::json;
use std::time::{Duration, Instant};

/// Android may call in (e.g. from the share sheet) while nativeStart is still
/// opening the database, so wait briefly instead of dropping the request.
fn wait_for_host() -> Option<Shared> {
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        if let Some(s) = shared() {
            return Some(s);
        }
        if Instant::now() >= deadline {
            tracing::error!("Rivium host is not running; call nativeStart first");
            return None;
        }
        std::thread::sleep(Duration::from_millis(50));
    }
}

fn read_string(env: &mut JNIEnv, s: &JString) -> Option<String> {
    env.get_string(s).ok().map(Into::into)
}

fn to_jstring(env: &JNIEnv, value: String) -> jstring {
    env.new_string(value).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn error_json(msg: impl std::fmt::Display) -> String {
    json!({ "ok": false, "error": msg.to_string() }).to_string()
}

/// Returns `{"ok":true,"id":..,"duplicate":..,"status":..}` or `{"ok":false,"error":..}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_apalis_riviumchallenge_RiviumBridge_nativeSaveShared(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
) -> jstring {
    let Some(text) = read_string(&mut env, &text) else {
        return to_jstring(&env, error_json("unreadable text"));
    };
    let Some(url) = extract::find_link(&text) else {
        return to_jstring(&env, error_json("No link found in what was shared"));
    };
    let Some(host) = wait_for_host() else {
        return to_jstring(&env, error_json("Reader is not running"));
    };
    let result = host.runtime.block_on(db::save_link(&host.pool, &url));
    let body = match result {
        Ok(o) => json!({ "ok": true, "id": o.id, "duplicate": o.duplicate, "status": o.status, "url": url.as_str() })
            .to_string(),
        Err(e) => error_json(e),
    };
    to_jstring(&env, body)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_apalis_riviumchallenge_RiviumBridge_nativeListArticles(
    env: JNIEnv,
    _class: JClass,
    limit: jint,
) -> jstring {
    let rows = wait_for_host()
        .and_then(|h| h.runtime.block_on(db::list_articles(&h.pool, limit as i64)).ok())
        .unwrap_or_default();
    to_jstring(&env, serde_json::to_string(&rows).unwrap_or_else(|_| "[]".into()))
}

/// Full article including body, or an empty string. Marks it as read.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_apalis_riviumchallenge_RiviumBridge_nativeOpenArticle(
    mut env: JNIEnv,
    _class: JClass,
    id: JString,
) -> jstring {
    let row = read_string(&mut env, &id)
        .zip(wait_for_host())
        .and_then(|(id, h)| h.runtime.block_on(db::open_article(&h.pool, &id)).ok().flatten());
    let body = row.and_then(|r| serde_json::to_string(&r).ok()).unwrap_or_default();
    to_jstring(&env, body)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_apalis_riviumchallenge_RiviumBridge_nativeRetryArticle(
    mut env: JNIEnv,
    _class: JClass,
    id: JString,
) -> jboolean {
    let ok = read_string(&mut env, &id)
        .zip(wait_for_host())
        .and_then(|(id, h)| h.runtime.block_on(db::retry(&h.pool, &id)).ok())
        .unwrap_or(false);
    if ok { JNI_TRUE } else { JNI_FALSE }
}

/// Re-queues links that failed for network reasons. Returns how many.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_apalis_riviumchallenge_RiviumBridge_nativeRetryFailed(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    wait_for_host()
        .and_then(|h| h.runtime.block_on(db::retry_failed(&h.pool)).ok())
        .unwrap_or(0) as jint
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_apalis_riviumchallenge_RiviumBridge_nativeDeleteArticle(
    mut env: JNIEnv,
    _class: JClass,
    id: JString,
) -> jboolean {
    let ok = read_string(&mut env, &id)
        .zip(wait_for_host())
        .and_then(|(id, h)| h.runtime.block_on(db::delete(&h.pool, &id)).ok())
        .unwrap_or(false);
    if ok { JNI_TRUE } else { JNI_FALSE }
}

/// Saved/failed articles not yet announced, as a JSON array. Each is returned once.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_apalis_riviumchallenge_RiviumBridge_nativeTakeFinished(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let rows = shared()
        .and_then(|h| h.runtime.block_on(db::take_unnotified(&h.pool)).ok())
        .unwrap_or_default();
    to_jstring(&env, serde_json::to_string(&rows).unwrap_or_else(|_| "[]".into()))
}

/// `{"unread":n,"pending":n,"failed":n}`; zeros when the host isn't running.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_apalis_riviumchallenge_RiviumBridge_nativeCounts(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let counts = shared()
        .and_then(|h| h.runtime.block_on(db::counts(&h.pool)).ok())
        .unwrap_or_default();
    to_jstring(&env, serde_json::to_string(&counts).unwrap_or_else(|_| "{}".into()))
}
