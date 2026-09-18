const fs = require("fs");
const path = require("path");

/** The one rules file, read from the repo root so this can never test a stale copy. */
const RULES_PATH = path.join(__dirname, "..", "..", "database.rules.json");
const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require("@firebase/rules-unit-testing");
const { ref, set, update, remove, get } = require("firebase/database");

const LEADER = "leaderUid";
const CO_LEADER = "coLeaderUid";
const RIDER = "riderUid";
const STRANGER = "strangerUid";
const ROOM = "room-1";
const CODE = "7KQ2WX";

let env;
let passed = 0;
let failed = 0;

async function check(name, promise) {
  try {
    await promise;
    console.log("  ok   " + name);
    passed++;
  } catch (error) {
    const first = error && error.message ? error.message.split("\n")[0] : String(error);
    console.log("  FAIL " + name + "  --> " + first);
    failed++;
  }
}

/** Seeds a ride in progress, past the rules, the way the app's own history would have made it. */
async function seed() {
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.database();
    await set(ref(db, `codes/${CODE}`), ROOM);
    await set(ref(db, `rooms/${ROOM}/meta`), {
      code: CODE,
      name: "Sunday run",
      visibility: "INVITE_ONLY",
      maxRiders: 6,
      state: "RIDING",
      leaderId: LEADER,
      createdAt: 1756000000000,
    });
    await set(ref(db, `rooms/${ROOM}/members`), {
      [LEADER]: { displayName: "Leader", role: "LEADER" },
      [CO_LEADER]: { displayName: "Co", role: "CO_LEADER" },
      [RIDER]: { displayName: "Rider", role: "RIDER" },
    });
    await set(ref(db, `positions/${ROOM}/${RIDER}`), {
      lat: 41.7, lon: 44.8, atMillis: 1756000001000, speedMps: 12.5, bearingDeg: 90,
    });
    await set(ref(db, `events/${ROOM}/e1`), {
      type: "Joined", at: 1756000000500, riderId: RIDER,
    });
  });
}

function db(uid) {
  return uid === null
    ? env.unauthenticatedContext().database()
    : env.authenticatedContext(uid).database();
}

const sample = (atMillis) => ({ lat: 41.71, lon: 44.81, atMillis, speedMps: 10, bearingDeg: 12 });

async function run() {
  env = await initializeTestEnvironment({
    projectId: "ridetogether-rules",
    database: {
      host: "127.0.0.1",
      port: 9110,
      rules: fs.readFileSync(RULES_PATH, "utf8"),
    },
  });

  // ─────────────── where people are: the node that matters most
  console.log("\npositions — a live feed of where named people physically are");
  await seed();

  await check(
    "a stranger cannot see where the group is",
    assertFails(get(ref(db(STRANGER), `positions/${ROOM}`))),
  );
  await check(
    "a signed-out client cannot see where the group is",
    assertFails(get(ref(db(null), `positions/${ROOM}`))),
  );
  await check(
    "a member can see where the group is",
    assertSucceeds(get(ref(db(RIDER), `positions/${ROOM}`))),
  );
  await check(
    "a rider can report their own position",
    assertSucceeds(set(ref(db(RIDER), `positions/${ROOM}/${RIDER}`), sample(1756000002000))),
  );
  await check(
    "a rider cannot plant a position for somebody else",
    assertFails(set(ref(db(RIDER), `positions/${ROOM}/${CO_LEADER}`), sample(1756000002000))),
  );
  await check(
    "a stranger cannot plant a position in a ride they are not on",
    assertFails(set(ref(db(STRANGER), `positions/${ROOM}/${STRANGER}`), sample(1756000002000))),
  );
  await check(
    "a position off the globe is rejected",
    assertFails(set(ref(db(RIDER), `positions/${ROOM}/${RIDER}`), { lat: 999, lon: 44.8, atMillis: 1 })),
  );

  // ─────────────── who may control the ride
  console.log("\nroom control — leader and co-leader, which is what the domain says");
  await seed();

  await check(
    "the leader can pause the ride",
    assertSucceeds(set(ref(db(LEADER), `rooms/${ROOM}/meta/state`), "PAUSED")),
  );
  await check(
    "a co-leader can pause the ride",
    assertSucceeds(set(ref(db(CO_LEADER), `rooms/${ROOM}/meta/state`), "PAUSED")),
  );
  await check(
    "an ordinary rider cannot end the ride for everybody",
    assertFails(set(ref(db(RIDER), `rooms/${ROOM}/meta/state`), "ENDED")),
  );
  await check(
    "a stranger cannot end a ride they are not on",
    assertFails(set(ref(db(STRANGER), `rooms/${ROOM}/meta/state`), "ENDED")),
  );
  await check(
    "a state the app does not have is rejected",
    assertFails(set(ref(db(LEADER), `rooms/${ROOM}/meta/state`), "CANCELLED")),
  );

  // ─────────────── membership
  console.log("\nmembership — joining, leaving, and who may promote whom");
  await seed();

  await check(
    "anyone signed in can read a room's details, which is how you decide to join",
    assertSucceeds(get(ref(db(STRANGER), `rooms/${ROOM}/meta`))),
  );
  await check(
    "but the list of who is on the ride is members only",
    assertFails(get(ref(db(STRANGER), `rooms/${ROOM}/members`))),
  );
  await check(
    "a new rider can add themselves",
    assertSucceeds(
      set(ref(db(STRANGER), `rooms/${ROOM}/members/${STRANGER}`), { displayName: "New", role: "RIDER" }),
    ),
  );
  await check(
    "a rider cannot promote themselves to leader",
    assertFails(
      set(ref(db(RIDER), `rooms/${ROOM}/members/${RIDER}`), { displayName: "Rider", role: "LEADER" }),
    ),
  );
  await check(
    "nor to co-leader",
    assertFails(
      set(ref(db(RIDER), `rooms/${ROOM}/members/${RIDER}`), { displayName: "Rider", role: "CO_LEADER" }),
    ),
  );
  await check(
    "the leader can promote a rider to co-leader",
    assertSucceeds(
      set(ref(db(LEADER), `rooms/${ROOM}/members/${RIDER}`), { displayName: "Rider", role: "CO_LEADER" }),
    ),
  );
  await check(
    "a rider can leave",
    assertSucceeds(remove(ref(db(RIDER), `rooms/${ROOM}/members/${RIDER}`))),
  );
  await seed();
  await check(
    "a rider cannot throw somebody else off the ride",
    assertFails(remove(ref(db(RIDER), `rooms/${ROOM}/members/${CO_LEADER}`))),
  );
  await check(
    "the leader can remove a rider",
    assertSucceeds(remove(ref(db(LEADER), `rooms/${ROOM}/members/${RIDER}`))),
  );

  // ─────────────── the ride log
  console.log("\nevents — a log you can add to and nobody can rewrite");
  await seed();

  await check(
    "a member can append an event",
    assertSucceeds(
      set(ref(db(RIDER), `events/${ROOM}/e2`), { type: "FellBehind", at: 1756000003000, riderId: RIDER }),
    ),
  );
  await check(
    "nobody can edit an event that was already written",
    assertFails(set(ref(db(LEADER), `events/${ROOM}/e1`), { type: "Left", at: 1, riderId: LEADER })),
  );
  await check(
    "nor delete one, including the leader",
    assertFails(remove(ref(db(LEADER), `events/${ROOM}/e1`))),
  );
  await check(
    "a rider cannot log an event in another rider's name",
    assertFails(
      set(ref(db(RIDER), `events/${ROOM}/e3`), { type: "Crashed", at: 1756000004000, riderId: CO_LEADER }),
    ),
  );
  await check(
    "a stranger cannot append to a ride log",
    assertFails(
      set(ref(db(STRANGER), `events/${ROOM}/e4`), { type: "Joined", at: 1, riderId: STRANGER }),
    ),
  );

  // ─────────────── join codes
  console.log("\njoin codes — claimable once, never re-pointed");
  await seed();

  await check(
    "a free code can be claimed",
    assertSucceeds(set(ref(db(STRANGER), "codes/9ABCDE"), "room-2")),
  );
  await check(
    "a taken code cannot be pointed at another room",
    assertFails(set(ref(db(STRANGER), `codes/${CODE}`), "room-evil")),
  );
  await check(
    "a signed-out client cannot claim a code",
    assertFails(set(ref(db(null), "codes/ZZZZZZ"), "room-3")),
  );
  await check(
    "anyone signed in can look a code up, which is how joining starts",
    assertSucceeds(get(ref(db(STRANGER), `codes/${CODE}`))),
  );

  // ─────────────── creating a room
  console.log("\ncreating a room");
  await env.clearDatabase();

  await check(
    "you can create a room with yourself as leader",
    assertSucceeds(
      set(ref(db(LEADER), "rooms/room-new/meta"), {
        code: "ABCDEF", name: "New ride", visibility: "INVITE_ONLY",
        maxRiders: 4, state: "LOBBY", leaderId: LEADER, createdAt: 1756000000000,
      }),
    ),
  );
  await check(
    "you cannot create a room with somebody else installed as leader",
    assertFails(
      set(ref(db(STRANGER), "rooms/room-hijack/meta"), {
        code: "BCDEFG", name: "Not mine", visibility: "INVITE_ONLY",
        maxRiders: 4, state: "LOBBY", leaderId: LEADER, createdAt: 1756000000000,
      }),
    ),
  );
  await check(
    "a room bigger than the app supports is rejected",
    assertFails(
      set(ref(db(LEADER), "rooms/room-huge/meta"), {
        code: "CDEFGH", name: "Too big", visibility: "PUBLIC",
        maxRiders: 50, state: "LOBBY", leaderId: LEADER, createdAt: 1756000000000,
      }),
    ),
  );

  // ─────────────── nothing is open by default
  console.log("\nthe closed default");
  await check(
    "the root is not readable",
    assertFails(get(ref(db(LEADER), "/"))),
  );
  await check(
    "an invented path is not writable",
    assertFails(set(ref(db(LEADER), "somethingNobodyThoughtOf/x"), 1)),
  );

  await env.cleanup();

  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed === 0 ? 0 : 1);
}

run().catch((error) => {
  console.error(error);
  process.exit(1);
});
