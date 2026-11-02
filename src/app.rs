use std::{path::PathBuf, time::Duration};

use rivium::{Kind, Result, Service, service};
use rivium_host::{App, Context};
use serde::{Deserialize, Serialize};

use crate::jobs::run_worker;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LinkQueueConfig {
    #[serde(default)]
    pub data_dir: String,
}

impl Default for LinkQueueConfig {
    fn default() -> Self {
        Self {
            data_dir: "linkqueue-data".to_owned(),
        }
    }
}

pub struct LinkQueueApp;

impl App for LinkQueueApp {
    const NAME: &'static str = "linkqueue";
    const VERSION: &'static str = env!("CARGO_PKG_VERSION");

    type Config = LinkQueueConfig;

    fn services(config: &Self::Config, _: &Context<Self::Config>) -> Result<Vec<Box<dyn Service>>> {
        let data_directory = PathBuf::from(&config.data_dir);

        Ok(vec![service(
            "apalis-worker",
            Kind::Frontline,
            move |ctx| {
                let data_directory = data_directory.clone();
                async move {
                    loop {
                        if let Err(error) = std::fs::create_dir_all(&data_directory) {
                            return Err(rivium::Error::internal(format!(
                                "Could not create the queue data directory: {error}"
                            )));
                        }
                        ctx.ready();

                        let worker = run_worker(&data_directory, ctx.stopped());
                        tokio::pin!(worker);
                        tokio::select! {
                            result = &mut worker => match result {
                                Ok(()) => return Ok(()),
                                Err(error) => {
                                    eprintln!("Apalis worker stopped: {error}");
                                    tokio::select! {
                                        _ = ctx.stopped() => return Ok(()),
                                        _ = tokio::time::sleep(Duration::from_secs(1)) => {}
                                    }
                                }
                            },
                            _ = ctx.stopped() => return Ok(()),
                        }
                    }
                }
            },
        )])
    }
}
