use clap::{Parser, Subcommand};

mod commands;
mod ipc_client;

#[derive(Parser)]
#[command(name = "nxfr", about = "NXFR file transfer client")]
struct Cli {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// Show daemon status
    Status,
    /// List discovered and paired devices
    Devices,
    /// Enable or disable receiving
    Receive {
        #[arg(long, conflicts_with = "disable")]
        enable: bool,
        #[arg(long, conflicts_with = "enable")]
        disable: bool,
    },
    /// Send a file to a device
    Send {
        /// Path to file
        path: String,
        /// Target device name or ID
        #[arg(long)]
        to: Option<String>,
        /// Target address (IP:PORT)
        #[arg(long)]
        addr: Option<String>,
        /// Retry a transfer by ID
        #[arg(long)]
        retry: Option<String>,
    },
    /// Pair with a device
    Pair {
        /// Device name or ID
        device: String,
        /// Target address (IP:PORT)
        #[arg(long)]
        addr: Option<String>,
    },
    /// Watch daemon events
    Watch,
    /// Accept a transfer offer
    Accept {
        /// Transfer ID
        transfer_id: String,
    },
    /// Reject a transfer offer
    Reject {
        /// Transfer ID
        transfer_id: String,
    },
    /// Unpair a device
    Unpair {
        /// Device name or ID
        device: String,
    },
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let cli = Cli::parse();

    match cli.command {
        Commands::Status => commands::status::handle().await,
        Commands::Devices => commands::devices::handle().await,
        Commands::Receive { enable, disable } => {
            if !enable && !disable {
                anyhow::bail!("Specify --enable or --disable");
            }
            commands::receive::handle(enable).await
        }
        Commands::Send {
            path,
            to,
            addr,
            retry,
        } => commands::send_cmd::handle(path, to, addr, retry).await,
        Commands::Pair { device, addr } => commands::pair::handle(device, addr).await,
        Commands::Watch => commands::watch::handle().await,
        Commands::Accept { transfer_id } => commands::accept::handle(transfer_id).await,
        Commands::Reject { transfer_id } => commands::reject::handle(transfer_id).await,
        Commands::Unpair { device } => commands::unpair::handle(device).await,
    }
}
