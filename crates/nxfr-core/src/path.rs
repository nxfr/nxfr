#![forbid(unsafe_code)]

use nxfr_common::error::PathError;
use nxfr_common::limits::{MAX_PATH_COMPONENT, MAX_RELATIVE_PATH};

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
}
