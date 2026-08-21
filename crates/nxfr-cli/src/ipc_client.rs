use anyhow::Context;
use std::path::PathBuf;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::unix::{OwnedReadHalf, OwnedWriteHalf};
use tokio::net::UnixStream;

pub struct IpcClient {
    reader: BufReader<OwnedReadHalf>,
    writer: OwnedWriteHalf,
}

impl IpcClient {
    pub async fn connect() -> anyhow::Result<Self> {
        let mut socket_path = dirs::state_dir().unwrap_or_else(|| {
            let mut p = dirs::home_dir().unwrap_or_else(|| PathBuf::from("/tmp"));
            p.push(".local");
            p.push("state");
            p
        });
        socket_path.push("nxfr");
        socket_path.push("nxfr.sock");

        let stream = match UnixStream::connect(&socket_path).await {
            Ok(s) => s,
            Err(_) => {
                anyhow::bail!("nxfr-daemon is not running. Start it with: nxfr-daemon &");
            }
        };

        let (read_half, write_half) = stream.into_split();
        Ok(Self {
            reader: BufReader::new(read_half),
            writer: write_half,
        })
    }

    pub async fn send_request(
        &mut self,
        req: &serde_json::Value,
    ) -> anyhow::Result<serde_json::Value> {
        self.send_followup(req).await?;

        let mut line = String::new();
        self.reader
            .read_line(&mut line)
            .await
            .context("Failed to read response")?;

        if line.is_empty() {
            anyhow::bail!("Daemon closed connection unexpectedly");
        }

        let resp: serde_json::Value =
            serde_json::from_str(&line).context("Invalid JSON response")?;
        Ok(resp)
    }

    pub async fn read_event(&mut self) -> anyhow::Result<Option<serde_json::Value>> {
        let mut line = String::new();
        let bytes_read = self.reader.read_line(&mut line).await?;

        if bytes_read == 0 {
            return Ok(None);
        }

        let event: serde_json::Value = serde_json::from_str(&line).context("Invalid JSON event")?;
        Ok(Some(event))
    }

    pub async fn send_followup(&mut self, req: &serde_json::Value) -> anyhow::Result<()> {
        let mut json = serde_json::to_string(req).context("Failed to serialize request")?;
        json.push('\n');
        self.writer
            .write_all(json.as_bytes())
            .await
            .context("Failed to send request")?;
        self.writer.flush().await?;
        Ok(())
    }
}
