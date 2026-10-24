//! Android system HID registration policy. Numeric values form the private JNI protocol.
//! Repeated UI starts and duplicate platform callbacks must not register extra profiles.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(i32)]
pub(crate) enum State {
    Stopped = 0,
    WaitingForProxy = 1,
    Registering = 2,
    Ready = 3,
    Suspended = 4,
}
#[derive(Clone, Copy, Debug)]
#[repr(i32)]
pub(crate) enum Event {
    Start = 0,
    ProxyReady = 1,
    Registered = 2,
    Unregistered = 3,
    Retry = 4,
    Stop = 5,
}
impl State {
    pub(crate) fn transition(self, event: Event) -> Self {
        use Event::*;
        use State::*;
        match (self, event) {
            (_, Stop) => Stopped,
            (Stopped, Start) => WaitingForProxy,
            (WaitingForProxy, ProxyReady) | (Suspended, Retry) => Registering,
            (Registering | Suspended, Registered) => Ready,
            (Registering | Ready, Unregistered) => Suspended,
            _ => self,
        }
    }
}
pub(crate) fn transition(state: i32, event: i32) -> i32 {
    let state = match state {
        0 => State::Stopped,
        1 => State::WaitingForProxy,
        2 => State::Registering,
        3 => State::Ready,
        4 => State::Suspended,
        _ => return 0,
    };
    let event = match event {
        0 => Event::Start,
        1 => Event::ProxyReady,
        2 => Event::Registered,
        3 => Event::Unregistered,
        4 => Event::Retry,
        5 => Event::Stop,
        _ => return 0,
    };
    state.transition(event) as i32
}
#[cfg(test)]
mod tests {
    use super::{Event::*, State::*, *};
    #[test]
    fn startup_registers_once_despite_repeated_starts_and_proxy_callbacks() {
        let mut state = Stopped.transition(Start);
        assert_eq!(state, WaitingForProxy);
        for _ in 0..20 {
            assert_eq!(state.transition(Start), state);
        }
        state = state.transition(ProxyReady);
        assert_eq!(state, Registering);
        for event in [Start, ProxyReady, Retry] {
            assert_eq!(state.transition(event), Registering);
        }
        state = state.transition(Registered);
        for event in [Start, ProxyReady, Retry, Registered] {
            assert_eq!(state.transition(event), Ready);
        }
    }
    #[test]
    fn authoritative_success_callback_overrides_vendor_false_return() {
        // RP5 reports registerApp=false immediately, then registered=true.
        let state = Stopped
            .transition(Start)
            .transition(ProxyReady)
            .transition(Unregistered);
        assert_eq!(state, Suspended);
        assert_eq!(state.transition(Registered), Ready);
        assert_eq!(state.transition(Registered).transition(Start), Ready);
        assert_eq!(state.transition(Stop).transition(Registered), Stopped);
    }
    #[test]
    fn foreground_retry_requires_registration_loss() {
        assert_eq!(Ready.transition(Retry), Ready);
        assert_eq!(Registering.transition(Retry), Registering);
        assert_eq!(Ready.transition(Unregistered), Suspended);
        assert_eq!(Suspended.transition(Retry), Registering);
        assert_eq!(Registering.transition(Unregistered), Suspended);
    }
    #[test]
    fn stopped_sessions_ignore_late_callbacks() {
        for state in [Stopped, WaitingForProxy, Registering, Ready, Suspended] {
            let stopped = state.transition(Stop);
            for event in [ProxyReady, Registered, Unregistered, Retry, Stop] {
                assert_eq!(stopped.transition(event), Stopped);
            }
        }
    }
    #[test]
    fn repeated_complete_sessions_do_not_accumulate_registration_state() {
        for _ in 0..100 {
            let state = Stopped
                .transition(Start)
                .transition(ProxyReady)
                .transition(Registered);
            assert_eq!(state, Ready);
            assert_eq!(state.transition(Stop), Stopped);
        }
    }
    #[test]
    fn invalid_jni_values_fail_closed() {
        for value in [-1, 6, i32::MAX] {
            assert_eq!(transition(value, 0), 0);
            assert_eq!(transition(3, value), 0);
        }
        assert_eq!(transition(0, 0), 1);
        assert_eq!(transition(1, 1), 2);
        assert_eq!(transition(2, 2), 3);
        assert_eq!(transition(3, 3), 4);
        assert_eq!(transition(4, 4), 2);
        assert_eq!(transition(2, 5), 0);
    }
}
