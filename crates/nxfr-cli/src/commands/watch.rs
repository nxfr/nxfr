use crate::ipc_client::IpcClient;
use dialoguer::Confirm;
use indicatif::{ProgressBar, ProgressStyle};
use serde_json::json;

pub async fn handle() -> anyhow::Result<()> {
    let mut client = IpcClient::connect().await?;
    let req = json!({ "cmd": "watch" });
    client.send_followup(&req).await?;

    let mut pb: Option<ProgressBar> = None;

    // Wait for the initial ack before entering the event loop.
    if let Some(ack) = client.read_event().await? {
        if ack["ok"].as_bool() != Some(true) {
            let err = ack["error"].as_str().unwrap_or("Unknown error");
            anyhow::bail!("Watch failed: {err}");
        }
    }
    println!("\u{1f514} Watching for transfer events... (Ctrl+C to stop)");

    while let Some(event) = client.read_event().await? {
        match event["type"].as_str() {
            Some("response") => {
                // Silently consume follow-up ack responses (e.g. transfer_confirm).
            }
            Some("transfer_offer") => {
                let id = event["transfer_id"].as_str().unwrap_or("?");
                let name = event["display_name"].as_str().unwrap_or("?");
                let size = event["total_size"].as_u64().unwrap_or(0);
                let sender = event["peer_name"].as_str().unwrap_or("?");
                let files = event["total_files"].as_u64().unwrap_or(1);

                println!("\nIncoming transfer offer:");
                println!("  Transfer ID: {id}");
                println!("  From: {sender}");
                println!("  Name: {name}");
                println!("  Files: {files}, Size: {size} bytes");

                let confirm = Confirm::new()
                    .with_prompt("Accept this transfer?")
                    .default(true)
                    .interact()?;

                let req = json!({
                    "cmd": "transfer_confirm",
                    "transfer_id": id,
                    "accepted": confirm,
                });
                client.send_followup(&req).await?;
                // Don't print here — the broadcast "transfer_resolved" event
                // will print the resolved status exactly once.
            }
            Some("receive_progress") => {
                let bytes = event["bytes_received"].as_u64().unwrap_or(0);
                let total = event["total_bytes"].as_u64().unwrap_or(100);

                if pb.is_none() {
                    let bar = ProgressBar::new(total);
                    bar.set_style(
                        ProgressStyle::default_bar()
                            .template(
                                "{spinner:.green} [{elapsed_precise}] [{bar:40.cyan/blue}] {bytes}/{total_bytes} ({eta})",
                            )?
                            .progress_chars("#>-"),
                    );
                    pb = Some(bar);
                }

                if let Some(p) = &pb {
                    p.set_length(total);
                    p.set_position(bytes);
                }
            }
            Some("transfer_complete") => {
                if let Some(p) = pb.take() {
                    p.finish_with_message("Transfer complete!");
                }
                let tid = event["transfer_id"].as_str().unwrap_or("?");
                println!("Transfer complete: {tid}");
            }
            Some("transfer_resolved") => {
                let id = event["transfer_id"].as_str().unwrap_or("?");
                let accepted = event["accepted"].as_bool().unwrap_or(false);
                let reason = event["reason"].as_str().unwrap_or("");
                if accepted {
                    println!("Transfer {id} accepted.");
                } else {
                    println!("Transfer {id} rejected. Reason: {reason}");
                }
            }
            Some("sas_prompt") => {
                let peer = event["peer_name"].as_str().unwrap_or("?");
                let sas = event["sas_code"].as_str().unwrap_or("?");
                println!("\nPairing with: {peer}");
                println!("SAS Code: {sas}");
                println!("Verify this code matches the other device.");
            }
            Some("pair_success") => {
                let name = event["device_name"].as_str().unwrap_or("?");
                println!("Successfully paired with: {name}");
            }
            Some("error") => {
                if let Some(p) = pb.take() {
                    p.finish_and_clear();
                }
                let err_msg = event["message"].as_str().unwrap_or("Unknown error");
                eprintln!("Error: {err_msg}");
            }
            _ => {}
        }
    }

    Ok(())
}
