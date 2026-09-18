# Security-rules tests

`database.rules.json` is the only thing between a ride and anyone with an account. A group ride is a
live feed of where several named people physically are, minute by minute, so a mistake here does not
leak a preference — it lets a stranger watch somebody ride home.

Rules are also the kind of code nobody reads twice and nothing type-checks, so they get a real test
run against the actual Firebase database emulator, using the actual rules file read from the repo
root. There is no second copy to drift.

```sh
cd tools/rules-tests && npm install && npm test
```

Needs Node and a JDK, because the emulator is a Java process. CI runs exactly this.

## What is asserted

Each test is written as the sentence a person would say, because that is what a rule is for:

- **positions** — a stranger cannot see where the group is, a member can, and a rider can report
  only their own position. That last one matters more than it looks: without it one member could
  plant false positions for the others and every phone's alert engine would faithfully announce
  that somebody had fallen behind, or crashed.
- **room control** — the leader *and a co-leader* may pause or end a ride; an ordinary rider may
  not. The original spec said "only the leader writes meta", which contradicted co-leaders being
  able to pause. The domain resolved that with `Role.canControlRoom`, and these rules follow it
  rather than inventing a second permission model that could disagree.
- **membership** — anyone signed in can read a room's details, since that is how you decide whether
  to join, but the list of who is on the ride is members only. A rider can add and remove
  themselves and cannot promote themselves or throw anybody else off.
- **events** — members append; nobody edits or deletes, including the leader. The log is what a ride
  summary is built from and what somebody would look at after an incident, and an editable history
  is not a history. Events are attributed to whoever wrote them, so a forged "crashed" is not
  possible.
- **join codes** — claimable once and never re-pointed, so nobody can aim an existing code at a room
  they control and collect the people trying to join the real one.
- **the closed default** — the root is unreadable and an invented path is unwritable, so a node
  somebody forgets to think about is shut rather than open.
