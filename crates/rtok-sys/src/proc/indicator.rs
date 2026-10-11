//! What a person sees while a child runs: a spinner, or a bar once the child reports a total.

use std::io::IsTerminal;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{LazyLock, Mutex, PoisonError};
use std::time::Duration;

use indicatif::{MultiProgress, ProgressBar, ProgressDrawTarget, ProgressStyle};

use super::parse::{Unit, Update};

/// The one place bars are drawn, so [`suspend`] hides all of them while rtok asks a question.
static MULTI: LazyLock<MultiProgress> =
    LazyLock::new(|| MultiProgress::with_draw_target(ProgressDrawTarget::stderr()));

static OFF: AtomicBool = AtomicBool::new(false);

/// Turn every indicator off for this process: MCP and hook contexts, where stderr belongs to a
/// host, not a person.
pub fn disable_progress() {
    OFF.store(true, Ordering::Relaxed);
}

/// Hide every live indicator while `f` runs (a prompt), and redraw them after.
pub fn suspend<R>(f: impl FnOnce() -> R) -> R {
    MULTI.suspend(f)
}

/// Pipes, CI, a disabled process and `RTOK_NO_PROGRESS=1` keep stderr byte-identical.
pub(crate) fn shown(stderr_is_tty: bool, disabled: bool, env: Option<&str>) -> bool {
    stderr_is_tty && !disabled && env != Some("1")
}

/// The indicator for a command labelled `label`: drawn on a terminal, otherwise [`Hidden`].
pub(crate) fn pick(label: &str) -> Box<dyn Indicator> {
    let env = std::env::var("RTOK_NO_PROGRESS").ok();
    if shown(
        std::io::stderr().is_terminal(),
        OFF.load(Ordering::Relaxed),
        env.as_deref(),
    ) {
        Box::new(TtyIndicator::new(&MULTI, label))
    } else {
        Box::new(Hidden)
    }
}

pub trait Indicator: Send + Sync {
    fn set(&self, update: &Update);
    fn message(&self, msg: &str);
    /// Clear the indicator while `f` writes to the terminal, then redraw it.
    fn suspend(&self, f: &mut dyn FnMut());
    fn finish(&self);
}

/// Draws nothing.
pub struct Hidden;

impl Indicator for Hidden {
    fn set(&self, _: &Update) {}
    fn message(&self, _: &str) {}
    fn suspend(&self, f: &mut dyn FnMut()) {
        f();
    }
    fn finish(&self) {}
}

pub struct TtyIndicator {
    multi: MultiProgress,
    bar: ProgressBar,
    label: String,
    unit: Mutex<Option<Unit>>,
}

impl TtyIndicator {
    pub fn new(multi: &MultiProgress, label: &str) -> Self {
        let bar = multi.add(ProgressBar::new_spinner());
        bar.set_style(style("{spinner:.cyan} {msg}"));
        bar.set_message(label.to_string());
        bar.enable_steady_tick(Duration::from_millis(120));
        Self {
            multi: multi.clone(),
            bar,
            label: label.to_string(),
            unit: Mutex::new(None),
        }
    }
}

fn style(template: &str) -> ProgressStyle {
    ProgressStyle::with_template(template).unwrap_or_else(|_| ProgressStyle::default_spinner())
}

fn bar_style(unit: Unit) -> ProgressStyle {
    style(match unit {
        Unit::Items => "{msg} [{bar:30.cyan/blue}] {pos}/{len}",
        Unit::Percent => "{msg} [{bar:30.cyan/blue}] {pos}%",
        Unit::Bytes => {
            "{msg} [{bar:30.cyan/blue}] {decimal_bytes}/{decimal_total_bytes} ({decimal_bytes_per_sec}, eta {eta})"
        }
    })
}

impl Indicator for TtyIndicator {
    fn set(&self, u: &Update) {
        if let Some(total) = u.total {
            let mut shown = self.unit.lock().unwrap_or_else(PoisonError::into_inner);
            if *shown != Some(u.unit) {
                self.bar.set_style(bar_style(u.unit));
                *shown = Some(u.unit);
            }
            self.bar.set_length(total);
            self.bar.set_position(u.pos);
        }
        if let Some(msg) = &u.msg {
            self.bar.set_message(format!("{} {msg}", self.label));
        }
    }

    fn message(&self, msg: &str) {
        self.bar.set_message(msg.to_string());
    }

    fn suspend(&self, f: &mut dyn FnMut()) {
        self.multi.suspend(f);
    }

    fn finish(&self) {
        self.bar.finish_and_clear();
    }
}

impl Drop for TtyIndicator {
    fn drop(&mut self) {
        self.bar.finish_and_clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hidden_multi() -> MultiProgress {
        MultiProgress::with_draw_target(ProgressDrawTarget::hidden())
    }

    #[test]
    fn nothing_is_drawn_off_a_terminal_or_when_switched_off() {
        assert!(shown(true, false, None));
        assert!(!shown(false, false, None));
        assert!(!shown(true, true, None));
        assert!(!shown(true, false, Some("1")));
        assert!(shown(true, false, Some("0")));
    }

    #[test]
    fn a_spinner_becomes_a_bar_on_the_first_measurable_update() {
        let ind = TtyIndicator::new(&hidden_multi(), "fetching…");
        assert_eq!(ind.bar.length(), None);
        ind.set(&Update {
            pos: 0,
            total: None,
            unit: Unit::Items,
            msg: Some("Counting".into()),
        });
        assert_eq!(
            (ind.bar.length(), ind.bar.message().as_str()),
            (None, "fetching… Counting")
        );
        ind.set(&Update {
            pos: 3,
            total: Some(10),
            unit: Unit::Items,
            msg: None,
        });
        assert_eq!((ind.bar.position(), ind.bar.length()), (3, Some(10)));
        ind.finish();
        assert!(ind.bar.is_finished());
    }

    #[test]
    fn suspend_runs_the_closure_once_for_both_indicators() {
        let mut ran = 0;
        Hidden.suspend(&mut || ran += 1);
        TtyIndicator::new(&hidden_multi(), "x").suspend(&mut || ran += 1);
        assert_eq!(ran, 2);
        assert_eq!(suspend(|| 7), 7);
    }
}
