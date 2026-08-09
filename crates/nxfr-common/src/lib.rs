//! # nxfr-common
//!
//! Shared types and error definitions for the NXFR protocol workspace.
//! This crate is dependency-free (except thiserror) and provides the
//! foundational types used across all NXFR crates.

pub mod error;
pub mod types;

pub use error::NxfrError;
pub use types::*;
