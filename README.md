# hl7-poc

Proof of concept: convert between HL7 and JSON in both directions in a Mule 4 application -
for both HL7 v2.x (pipe-delimited ER7 messages, via the open source HAPI HL7v2 library)
and HL7 v3 (RIM-based XML documents, e.g. CDA, via plain DataWeave). These are two
unrelated wire formats that happen to share a name; see "HL7 v3" below for why they're
handled by entirely different code.

## How it works: HL7 v2

HAPI's `GenericParser` parses any HL7 v2.x message into its object model (segments,
fields, repetitions, components, subcomponents) without needing per-message-type
structure classes.

- [Hl7ToJsonConverter.java](src/main/java/com/plative/hl7poc/Hl7ToJsonConverter.java)
  walks that generic object model and emits JSON directly.
- [JsonToHl7Converter.java](src/main/java/com/plative/hl7poc/JsonToHl7Converter.java)
  does the reverse: it walks JSON in that same shape to build a raw ER7 string, then
  parses and re-encodes it through HAPI's `GenericParser` so the final output is HAPI's
  own canonical, correctly-escaped serialization rather than hand-rolled string output.

Both work across HL7 versions and message types (ADT, ORU, SIU, …) without per-type
mapping code.

JSON shape (produced by `Hl7ToJsonConverter`, consumed by `JsonToHl7Converter`):

```json
{
  "messageType": "ADT_A01",
  "segments": [
    { "segment": "MSH", "fields": { "MSH-9": ["ADT", "A01"], "MSH-10": "MSG00001", ... } },
    { "segment": "PID", "fields": { "PID-5": ["JONES", "WILLIAM", "A", "III"], ... } }
  ]
}
```

Rules used to build that JSON:
- A field key is `SEGMENT-fieldNumber` (e.g. `PID-5`), matching standard HL7 notation.
- A field with a single component and no repetitions is a plain string.
- A composite field (has components, e.g. a name or address) is a JSON array of its
  component values, recursively (so a composite-of-composites nests arrays).
- A repeating field (e.g. a patient with two identifiers) is a JSON array of the above -
  repetitions are only unambiguous when each one is itself an array, e.g.
  `[["PATID1234","5","M11","ADT1","MR","MCM"],["123456789","","","USSSA","SS"]]` for
  `PID-3` (a repeating, composite identifier list).
- A field is omitted entirely if it (and every component/repetition in it) is empty. An
  empty *interior* component is kept as `""` so later components don't shift position
  (e.g. `123456789^^^USSSA^SS` keeps its two blank components); only a *trailing* run of
  empty components is dropped, matching how HL7 itself treats them as optional.

**Known limitation:** a flat JSON array of strings, e.g. `["(906)485-1234","(906)485-1234"]`,
is structurally identical whether it came from one composite field's components or from
two repetitions of a plain (non-composite) value - `Hl7ToJsonConverter` can't tell them
apart in the JSON, so `JsonToHl7Converter` always reconstructs it as the more common case
(one composite, joined with `^`). Confirmed against the sample message: `NK1-6` (repeating
phone numbers, `(906)485-1234~(906)485-1234`) round-trips as
`(906)485-1234^(906)485-1234` instead. Everything else in the sample - nested composites,
repeating *composite* fields, interior blank components, the MSH encoding characters
themselves - round-trips exactly. Fixing this fully would mean always wrapping single
repetitions in an array too (so a repeating field is never shape-identical to a composite
field), which was left out to keep the JSON readable for a POC.

The Mule flows call these classes through the Java module, e.g.
`java:invoke-static class="com.plative.hl7poc.Hl7ToJsonConverter" method="convertToJson(String)"`,
passing the raw text as `arg0`.

## How it works: HL7 v3

HL7 v3 is not a newer version of HL7 v2 - it's a different standard: RIM-based, always
XML (CDA clinical documents are the common case). HAPI's `hapi-base` (the library behind
the HL7 v2 flows above) has no concept of HL7 v3 and can't parse it.

The v3 flows don't use HAPI, or any HL7-specific library at all: since an HL7 v3 message
*is* well-formed XML, DataWeave's built-in XML reader/writer already do the whole
conversion.

- `hl7v3-to-json-file-flow` / `hl7v3-http-test-flow`: `output application/json` over
  `read(payload, "application/xml")`.
- `json-to-hl7v3-file-flow` / `json-to-hl7v3-http-test-flow`: `output application/xml`
  over `read(payload, "application/json")`.

No custom Java code, so none of the escaping/positional logic the HL7 v2 converters
needed applies here. The tradeoff is DataWeave's own generic XML<->JSON conventions
(attributes become `@name` keys, a repeated element becomes an array but a single
occurrence doesn't) rather than anything HL7-specific - the same kind of single-vs-array
ambiguity noted for HL7 v2 above is inherent to XML<->JSON in general, not something this
POC adds on top.

## Flows

### HL7 v2

HL7 to JSON:
- **hl7-file-to-json-flow** ([implementation/hl7-file-to-json-flow.xml](src/main/mule/implementation/hl7-file-to-json-flow.xml)):
  polls `hl7-files/input` for `*.hl7` files, converts each to JSON, writes
  `hl7-files/output/<name>.hl7.json`, and moves the source file to `hl7-files/processed`.
- **hl7-http-test-flow** ([implementation/hl7-http-test-flow.xml](src/main/mule/implementation/hl7-http-test-flow.xml)):
  `POST /hl7/parse` on port 8081, raw HL7 text in the body, JSON back in the response.

JSON to HL7:
- **json-to-hl7-file-flow** ([implementation/json-to-hl7-file-flow.xml](src/main/mule/implementation/json-to-hl7-file-flow.xml)):
  polls `hl7-files/json-input` for `*.json` files, converts each to an HL7 ER7 message,
  writes `hl7-files/hl7-output/<name>.json.hl7`, and moves the source file to
  `hl7-files/json-processed`.
- **json-to-hl7-http-test-flow** ([implementation/json-to-hl7-http-test-flow.xml](src/main/mule/implementation/json-to-hl7-http-test-flow.xml)):
  `POST /json/to-hl7` on port 8081, JSON in the body, raw HL7 text back in the response.

All four flows are file-poller / HTTP-test pairs for the same two `com.plative.hl7poc`
converter classes - the HTTP endpoints exist only to make manual testing fast; the file
flows are the ones meant to run unattended.

Directories are configured in [config/common.yaml](src/main/resources/config/common.yaml)
under `hl7.*` (HL7 -> JSON direction) and `json.*` (JSON -> HL7 direction), all relative
to `hl7.workingDirectory`. Sample inputs are checked in at
`hl7-files/input/sample-adt-a01.hl7` and `hl7-files/json-input/sample-adt-a01.json`.

### HL7 v3

HL7 v3 to JSON:
- **hl7v3-to-json-file-flow** ([implementation/hl7v3-to-json-file-flow.xml](src/main/mule/implementation/hl7v3-to-json-file-flow.xml)):
  polls `hl7-files/v3-input` for `*.xml` files, converts each to JSON, writes
  `hl7-files/v3-output/<name>.xml.json`, and moves the source file to `hl7-files/v3-processed`.
- **hl7v3-http-test-flow** ([implementation/hl7v3-http-test-flow.xml](src/main/mule/implementation/hl7v3-http-test-flow.xml)):
  `POST /hl7v3/parse` on port 8081, raw XML in the body, JSON back in the response.

JSON to HL7 v3:
- **json-to-hl7v3-file-flow** ([implementation/json-to-hl7v3-file-flow.xml](src/main/mule/implementation/json-to-hl7v3-file-flow.xml)):
  polls `hl7-files/json-v3-input` for `*.json` files, converts each to an HL7 v3 XML
  document, writes `hl7-files/v3-hl7-output/<name>.json.xml`, and moves the source file
  to `hl7-files/json-v3-processed`.
- **json-to-hl7v3-http-test-flow** ([implementation/json-to-hl7v3-http-test-flow.xml](src/main/mule/implementation/json-to-hl7v3-http-test-flow.xml)):
  `POST /json/to-hl7v3` on port 8081, JSON in the body, raw XML back in the response.

Directories for these four are configured under `hl7v3.*` and `jsonv3.*` in
[config/common.yaml](src/main/resources/config/common.yaml), same layout as the v2 ones.
A sample CDA-style document is checked in at `hl7-files/v3-input/sample-ccd.xml`.

## Build

```bash
mvn clean package -DskipTests
```

This has been run successfully against this project (Java 17, Maven 3.9), which packages
all eight flows and type-checks every DataWeave script. The HL7 v2 `Hl7ToJsonConverter` /
`JsonToHl7Converter` classes were also exercised directly (bypassing the Mule runtime, via
a standalone harness) against the sample ADT^A01 message: HL7 -> JSON -> HL7 -> JSON is
byte-for-byte stable, and HL7 -> JSON -> HL7 matches the original message except for the
known limitation above and one harmless trailing empty field (`PV1-16`) that HL7 doesn't
require and HAPI doesn't re-emit.

The HL7 v3 flows are plain `read()`/`write()` DataWeave, so they carry far less custom-code
risk than the v2 converters, but they have only been validated by the Maven build above
(which does type-check the DW scripts) - not against a running Mule instance with a real
HTTP request, since this environment's local standalone runtime doesn't survive past a
single command here. Worth a real deploy-and-curl pass before you rely on them.

### A note on running locally

CLAUDE.md documents `mvn mule:run` for local execution. On this workspace's pinned
`mule-maven-plugin` version (4.7.0) that goal does not exist (`mvn mule:run` fails with
`Could not find goal 'run'`) - this is a pre-existing condition of the plugin version,
not something introduced here. Use Anypoint Studio's **Run As > Mule Application**
against this project instead, or deploy the packaged JAR to CloudHub 2.0 with
`mvn deploy -DmuleDeploy`.

## Test with curl

```bash
curl --data-binary @hl7-files/input/sample-adt-a01.hl7 http://localhost:8081/hl7/parse
curl --data-binary @hl7-files/json-input/sample-adt-a01.json http://localhost:8081/json/to-hl7
curl --data-binary @hl7-files/v3-input/sample-ccd.xml http://localhost:8081/hl7v3/parse
curl --data-binary @<a JSON file produced by /hl7v3/parse> http://localhost:8081/json/to-hl7v3
```

All four endpoints expect a raw body (`--data-binary`), not a `--form`/multipart request.

## Dependencies added

| Dependency | Why |
|---|---|
| `ca.uhn.hapi:hapi-base:2.3` | Open source HL7v2 parser/encoder (HAPI). `slf4j-log4j12` / `log4j` 1.x excluded to avoid colliding with Mule's own log4j2 binding. Used only by the HL7 v2 flows. |
| `org.mule.module:mule-java-module:2.0.4` | Lets the HL7 v2 flows call the converter classes via `java:invoke-static`. Not needed by the HL7 v3 flows - those are pure DataWeave. |
| `org.mule.connectors:mule-file-connector:1.5.5` | Reads/writes HL7 v2, HL7 v3, and JSON files on disk. |

`mule-http-connector` and `mule-sockets-connector` were already present in the generated
project scaffold and back the test-only HTTP endpoints. No new dependency was needed for
HL7 v3 support - `ee:transform`/DataWeave is bundled with the Mule runtime.
