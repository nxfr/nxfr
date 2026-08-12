#![forbid(unsafe_code)]

use nxfr_common::error::PathError;
use nxfr_common::limits::{MAX_PATH_COMPONENT, MAX_RELATIVE_PATH};
use std::path::{Path, PathBuf};

pub fn sanitize_path(input: &str) -> Result<String, PathError> {
    if input.is_empty() {
        return Err(PathError::EmptyPath);
    }

    if input.contains('\\') {
        return Err(PathError::Backslash);
    }

    if input.starts_with('/') {
        return Err(PathError::AbsolutePath(input.to_string()));
    }

    // Check for drive letter X:
    let mut chars = input.chars();
    if let (Some(first), Some(':')) = (chars.next(), chars.next()) {
        if first.is_ascii_alphabetic() {
            return Err(PathError::AbsolutePath(input.to_string()));
        }
    }

    if input.contains('\0') {
        return Err(PathError::NullByte);
    }

    if let Some(c) = input.chars().find(|&c| c <= '\x1F' || c == '\x7F') {
        return Err(PathError::ControlCharacter(c as u8));
    }

    let mut normalized_components = Vec::new();
    for component in input.split('/') {
        if component.is_empty() || component == "." {
            continue;
        }

        if component == ".." {
            return Err(PathError::ParentTraversal(input.to_string()));
        }

        if component.len() > MAX_PATH_COMPONENT {
            return Err(PathError::ComponentTooLong {
                len: component.len(),
            });
        }

        let base_name = component.split('.').next().unwrap_or("");
        let base_upper = base_name.to_ascii_uppercase();
        if matches!(
            base_upper.as_str(),
            "CON"
                | "PRN"
                | "AUX"
                | "NUL"
                | "COM1"
                | "COM2"
                | "COM3"
                | "COM4"
                | "COM5"
                | "COM6"
                | "COM7"
                | "COM8"
                | "COM9"
                | "LPT1"
                | "LPT2"
                | "LPT3"
                | "LPT4"
                | "LPT5"
                | "LPT6"
                | "LPT7"
                | "LPT8"
                | "LPT9"
        ) {
            return Err(PathError::WindowsReservedName(input.to_string()));
        }

        normalized_components.push(component);
    }

    if normalized_components.is_empty() {
        return Err(PathError::EmptyPath);
    }

    let normalized_path = normalized_components.join("/");

    if normalized_path.len() > MAX_RELATIVE_PATH {
        return Err(PathError::PathTooLong {
            len: normalized_path.len(),
        });
    }

    Ok(normalized_path)
}

/// Defense-in-depth path jail: sanitize + canonicalize + assert within root.
///
/// 1. Sanitize the relative path (reject .., absolute, etc.).
/// 2. Join it to the download root.
/// 3. Canonicalize (resolve symlinks, .., etc.).
/// 4. Assert the final path starts_with the canonicalized root.
///
/// Returns the safe absolute path on success, PathError on jail escape.
pub fn resolve_safe_path(download_root: &Path, relative: &str) -> Result<PathBuf, PathError> {
    let sanitized = sanitize_path(relative)?;
    let canon_root = download_root
        .canonicalize()
        .map_err(|e| PathError::AbsolutePath(format!("cannot canonicalize root: {e}")))?;
    let joined = canon_root.join(&sanitized);
    // For new files that don't exist yet, canonicalize the parent.
    let check_path = if joined.exists() {
        joined
            .canonicalize()
            .map_err(|e| PathError::AbsolutePath(format!("cannot canonicalize dest: {e}")))?
    } else {
        // Canonicalize the parent directory, then append the filename.
        let parent = joined.parent().unwrap_or(&canon_root);
        let filename = joined.file_name().ok_or(PathError::EmptyPath)?;
        if parent.exists() {
            parent
                .canonicalize()
                .map_err(|e| PathError::AbsolutePath(format!("cannot canonicalize parent: {e}")))?
                .join(filename)
        } else {
            // Parent doesn't exist yet — we'll create it. Just check prefix.
            joined.clone()
        }
    };
    if !check_path.starts_with(&canon_root) {
        return Err(PathError::ParentTraversal(format!(
            "path escapes download root: {}",
            check_path.display()
        )));
    }
    Ok(joined)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_valid_paths() {
        assert_eq!(
            sanitize_path("vacation/beach.jpg").unwrap(),
            "vacation/beach.jpg"
        );
        assert_eq!(sanitize_path("src/main.rs").unwrap(), "src/main.rs");
        assert_eq!(sanitize_path("README.txt").unwrap(), "README.txt");
        assert_eq!(sanitize_path("test.bin").unwrap(), "test.bin");
    }

    #[test]
    fn test_normalization() {
        assert_eq!(sanitize_path("a//b///c").unwrap(), "a/b/c");
        assert_eq!(sanitize_path("./a/./b").unwrap(), "a/b");
        assert_eq!(sanitize_path("a/b/c/").unwrap(), "a/b/c");
    }

    #[test]
    fn test_empty_path() {
        assert!(matches!(sanitize_path(""), Err(PathError::EmptyPath)));
        // "///" starts with /, so it's AbsolutePath, not EmptyPath — tested in test_absolute_path
        assert!(matches!(sanitize_path("."), Err(PathError::EmptyPath)));
        assert!(matches!(sanitize_path("././."), Err(PathError::EmptyPath)));
    }

    #[test]
    fn test_parent_traversal() {
        assert!(matches!(
            sanitize_path("../../../etc/passwd"),
            Err(PathError::ParentTraversal(_))
        ));
    }

    #[test]
    fn test_backslash() {
        assert!(matches!(
            sanitize_path("..\\..\\windows\\system32"),
            Err(PathError::Backslash)
        ));
        assert!(matches!(
            sanitize_path("C:\\Windows"),
            Err(PathError::Backslash)
        ));
    }

    #[test]
    fn test_absolute_path() {
        assert!(matches!(
            sanitize_path("/etc/shadow"),
            Err(PathError::AbsolutePath(_))
        ));
        assert!(matches!(
            sanitize_path("C:foo"),
            Err(PathError::AbsolutePath(_))
        ));
        assert!(matches!(
            sanitize_path("d:bar"),
            Err(PathError::AbsolutePath(_))
        ));
    }

    #[test]
    fn test_null_byte() {
        assert!(matches!(
            sanitize_path("safe_file.txt\x00.exe"),
            Err(PathError::NullByte)
        ));
    }

    #[test]
    fn test_control_character() {
        assert!(matches!(
            sanitize_path("\x01hidden"),
            Err(PathError::ControlCharacter(1))
        ));
        assert!(matches!(
            sanitize_path("\x7f"),
            Err(PathError::ControlCharacter(127))
        ));
        assert!(matches!(
            sanitize_path("a\nb"),
            Err(PathError::ControlCharacter(10))
        ));
    }

    #[test]
    fn test_windows_reserved_names() {
        let reserved = ["CON", "PRN", "AUX", "NUL", "COM1", "COM9", "LPT1", "LPT9"];
        for name in reserved {
            assert!(matches!(
                sanitize_path(name),
                Err(PathError::WindowsReservedName(_))
            ));

            let with_ext = format!("{}.txt", name);
            assert!(matches!(
                sanitize_path(&with_ext),
                Err(PathError::WindowsReservedName(_))
            ));

            let lowercase = name.to_ascii_lowercase();
            assert!(matches!(
                sanitize_path(&lowercase),
                Err(PathError::WindowsReservedName(_))
            ));
        }

        assert!(matches!(
            sanitize_path("con.txt"),
            Err(PathError::WindowsReservedName(_))
        ));
        assert!(matches!(
            sanitize_path("Con.Txt.bak"),
            Err(PathError::WindowsReservedName(_))
        ));
    }

    #[test]
    fn test_component_too_long() {
        let long_comp = "a".repeat(256);
        let path = format!("foo/{}/bar", long_comp);
        assert!(matches!(
            sanitize_path(&path),
            Err(PathError::ComponentTooLong { len: 256 })
        ));
    }

    #[test]
    fn test_path_too_long() {
        let mut path = "a/".repeat(2048);
        path.push('b');
        assert!(matches!(
            sanitize_path(&path),
            Err(PathError::PathTooLong { len: _ })
        ));
    }

    #[test]
    fn test_reject_absolute_path_traversal() {
        let tmp = std::env::temp_dir().join("nxfr_test_jail");
        std::fs::create_dir_all(&tmp).unwrap();

        // Normal file inside root: OK.
        let result = resolve_safe_path(&tmp, "photo.jpg");
        assert!(result.is_ok(), "normal file should succeed: {result:?}");
        assert!(result.unwrap().starts_with(&tmp));

        // Subdirectory inside root: OK.
        let result = resolve_safe_path(&tmp, "subdir/file.txt");
        assert!(result.is_ok());

        // Traversal attempt: MUST fail.
        let result = resolve_safe_path(&tmp, "../../../etc/passwd");
        assert!(result.is_err(), "traversal must be rejected");

        // Absolute path: MUST fail.
        let result = resolve_safe_path(&tmp, "/etc/shadow");
        assert!(result.is_err(), "absolute path must be rejected");

        // Windows drive: MUST fail.
        let result = resolve_safe_path(&tmp, "C:\\Windows\\System32\\cmd.exe");
        assert!(result.is_err(), "windows drive must be rejected");

        // Null byte: MUST fail.
        let result = resolve_safe_path(&tmp, "safe\x00evil");
        assert!(result.is_err(), "null byte must be rejected");

        // Cleanup.
        let _ = std::fs::remove_dir_all(&tmp);
    }
}
