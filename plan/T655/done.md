`Queue::submit` used to check `in_flight` and then increment, so two concurrent admits could both pass a cap of 1. Admission is now one compare-and-swap that reserves the slot before the id is marked seen. A full queue leaves the id unmarked, so a later request can retry it. A duplicate and a poisoned `seen` lock drop the reservation. `Slot`'s drop releases the slot when the job ends or panics.

Landed in `628825f7` (#1056). This card records that the check already holds on main.

Check: no concurrent schedule admits more than `max_in_flight`, and every completion/error path releases its reservation.

Check result: `concurrent_distinct_ids_never_exceed_the_cap` runs 32 threads over 20 rounds at cap 1 and admits at most one, then sees `in_flight == 0`. `an_id_refused_at_the_cap_is_retried_once_a_slot_frees` retries an id that was not marked seen. `a_duplicate_hands_its_reservation_back` keeps a single slot for the running job.

Status: done 2026-10-19
