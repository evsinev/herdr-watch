//! Defensive accessors over the payload (design D8).
//!
//! Claude Code's status line payload is a document, not a schema: every field may
//! be absent, `null`, or of an unexpected type, and `rate_limits` is missing
//! entirely until the session's first API response. So types are checked at each
//! hop rather than assumed — the same discipline the Python renderer used.
//!
//! One Python guard disappears rather than being ported: `isinstance(value, bool)`
//! was needed because `bool` is an `int` subclass. Here `Value::Bool` is
//! structurally not `Value::Number`, so booleans fall out of `num` on their own.

use serde_json::Value;

/// One hop into an object. A missing key, or a value that is not an object,
/// yields `Null` — so lookups chain without `Option` plumbing, exactly like the
/// Python `as_dict(...).get(...)` idiom.
pub fn get<'a>(value: &'a Value, key: &str) -> &'a Value {
    match value {
        Value::Object(map) => map.get(key).unwrap_or(&Value::Null),
        _ => &Value::Null,
    }
}

/// Two hops, for the `payload.model.display_name` shape that dominates the renderer.
pub fn get2<'a>(value: &'a Value, first: &str, second: &str) -> &'a Value {
    get(get(value, first), second)
}

/// A finite number, or `None`. Booleans, strings, `null` and arrays are all
/// structurally excluded.
pub fn num(value: &Value) -> Option<f64> {
    match value {
        Value::Number(n) => n.as_f64().filter(|f| f.is_finite()),
        _ => None,
    }
}

/// A non-blank string, returned **unstripped** — the Python `as_text` tested
/// `value.strip()` but returned `value`, and the rendered line depends on that.
pub fn text(value: &Value) -> Option<&str> {
    value.as_str().filter(|s| !s.trim().is_empty())
}

/// Exactly `true` / exactly `false` — the Python code used `is True` / `is False`,
/// so a `1` is not a `true`.
pub fn is_exactly(value: &Value, expected: bool) -> bool {
    matches!(value, Value::Bool(b) if *b == expected)
}

/// `resets_at` as an integer.
///
/// The exact path is taken when the number is already an integer; a float is
/// truncated toward zero, which is what Python's `int()` does and what Java's
/// saturating `(long)` cast does for the values that can realistically appear.
pub fn to_i64(value: &Value) -> Option<i64> {
    match value {
        Value::Number(n) => {
            n.as_i64().or_else(|| n.as_f64().filter(|f| f.is_finite()).map(|f| f.trunc() as i64))
        }
        _ => None,
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::panic)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn booleans_are_not_numbers() {
        assert_eq!(num(&json!(true)), None);
        assert_eq!(num(&json!(false)), None);
    }

    #[test]
    fn integers_and_floats_are_both_numbers() {
        assert_eq!(num(&json!(27)), Some(27.0));
        assert_eq!(num(&json!(27.0)), Some(27.0));
        assert_eq!(num(&json!(7.000000000000001)), Some(7.000000000000001));
    }

    #[test]
    fn other_shapes_are_not_numbers() {
        for v in [json!("7"), json!(null), json!([7]), json!({"a": 7})] {
            assert_eq!(num(&v), None, "{v:?}");
        }
    }

    #[test]
    fn text_rejects_blank_but_returns_unstripped() {
        assert_eq!(text(&json!("  padded  ")), Some("  padded  "));
        assert_eq!(text(&json!("   ")), None);
        assert_eq!(text(&json!("")), None);
        assert_eq!(text(&json!(7)), None);
    }

    #[test]
    fn missing_hops_yield_null_rather_than_panicking() {
        let payload = json!({"model": {"id": "opus"}});
        assert_eq!(text(get2(&payload, "model", "id")), Some("opus"));
        assert_eq!(get2(&payload, "model", "absent"), &Value::Null);
        assert_eq!(get2(&payload, "absent", "id"), &Value::Null);
        assert_eq!(get2(&json!("not an object"), "a", "b"), &Value::Null);
    }

    #[test]
    fn is_exactly_is_strict_about_type() {
        assert!(is_exactly(&json!(true), true));
        assert!(!is_exactly(&json!(1), true));
        assert!(is_exactly(&json!(false), false));
        assert!(!is_exactly(&json!(0), false));
        assert!(!is_exactly(&Value::Null, false));
    }

    #[test]
    fn to_i64_takes_integers_exactly_and_truncates_floats() {
        assert_eq!(to_i64(&json!(1787883600)), Some(1787883600));
        assert_eq!(to_i64(&json!(1787883600.0)), Some(1787883600));
        assert_eq!(to_i64(&json!(1787883600.9)), Some(1787883600));
        assert_eq!(to_i64(&json!("1787883600")), None);
        assert_eq!(to_i64(&json!(true)), None);
    }
}
