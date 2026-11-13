# How Ya Bean?

Tim Tripcony's *How Ya Bean?* OpenNTF demo: a contact CRUD application for HCL/IBM
Domino XPages that deliberately does **not** use `xp:dominoDocument`. A hand-rolled
document model — a bean/map hybrid driven from EL — sits between the page and the
`NotesDocument`, so a page binds to typed properties where they exist and to document
items everywhere else.

The repository is an on-disk project (ODP) for Domino Designer: design elements only,
no documents. Designer's `PhysicalToNsfSynBuilder` synchronises it into an NSF.

For the full review, the finding list and the roadmap, see [REVIEW.md](REVIEW.md). This
file documents how the application and its storage layer work.

## Requirements

| Component | Notes |
|---|---|
| Domino server | 14.5+ (JVM 21). The compiler level in `.settings/org.eclipse.jdt.core.prefs` is pinned to 21 so it matches the JVM Designer 14.5 runs on; the source uses records and pattern matching, so an older target means rewriting those usages, not just the three preference values. |
| Domino Designer | to open the ODP and sync it into an NSF. There is no build file; see [Building](#building). |
| Extension Library | `com.ibm.xsp.extlib` (`xsp.library.depends` in `WebContent/WEB-INF/xsp.properties`). |
| Third-party jar | none. The repository ships no external library: the codec writes and reads its binary format itself (see [Storage](#storage)). |
| Theme | `Resources/Themes/howyabean.theme` extends `bootswatch_united`; pages use Bootstrap 3 markup. |

## Layout

| Path | Contents |
|---|---|
| `XPages/index.xsp` | Contact list with a pager, rendered from the `contacts` view. |
| `XPages/contact.xsp` | Detail/editor page; reads the UNID from `?id=`, saves via `#{contact.save}`. |
| `XPages/crud.xsp` | List and editor combined on one page. |
| `CustomControls/contactEditor.xsp` | Shared editor (`xc:contactEditor`): the fields, the phone book with add/remove, save and edit. Used by `contact.xsp` and `crud.xsp`, so both forms stay identical. `refreshTarget` names the page areas (space separated client ids) a save or edit should refresh; empty means the editor itself. |
| `CustomControls/contactList.xsp` | Shared contact list (`xc:contactList`): `layout="names"` for the sidebar, `layout="table"` for the crud page; owns the view data source, the pager and the delete action. `refreshTarget` does the same for a delete. |
| `CustomControls/navbar.xsp` | Navigation bar (composite component `xc:navbar`). |
| `Code/Java/org/openntf/howyabean/model/` | `ContactModel` (the bean the pages bind to), `PhoneNumber` (one repeat row). |
| `Code/Java/org/openntf/xsp/model/` | `AbstractMapModel` → `AbstractDocumentMapModel` → `AbstractSmartDocumentModel`, the three-layer model. |
| `Code/Java/org/openntf/xsp/util/` | `DominoUtil` (document read/write), `LogUtil`, `Recycle` and `MimeScope` (resource scopes). |
| `Code/Java/org/openntf/xsp/util/codec/` | Storage codecs: `ValueCodec`, `ValueCodecs`, `MessagePackCodec`, `Structured`, `StructuredTypes`, `StructuredValues`. |
| `Forms/contact.form`, `Views/contacts.view` | The `contact` form and the `contacts` view (`Form = "contact"`, collated by `lastName`, `firstName`). |
| `WebContent/WEB-INF/` | `faces-config.xml` (bean `contact`, view scope), `xsp.properties`, `lib/`. |

## How a page works

```
contact.xsp ──EL──► ContactModel        (view scope, faces-config.xml)
                      │
                      ├─ AbstractSmartDocumentModel   bean property or document item, per key
                      ├─ AbstractDocumentMapModel     items ⇄ document, load/save hooks
                      └─ AbstractMapModel             DataObject over Map<Object,Object>
```

* Creating a page without `?id=` puts the model into edit mode (`AbstractSmartDocumentModel`
  turns edit mode on when there is no UNID or `action=edit` is passed).
* `load(unid)` reads the document's items into the value map, leaving out Domino's own MIME
  bookkeeping and `$REF`/`$Conflict`/`$Revisions` items. A stored MIME value is decoded; any
  other MIME part (a body, an attachment) falls back to the wrapped `DominoDocument` value
  instead of being dropped, and `null` is never written back over a readable item.
* `save()` runs `querySave()` (the model copies derived values into the map here), writes
  each map entry through `replaceItemValue`, saves the document and runs `postSave()`.
  Failures become a `FacesMessage`, which `contact.xsp` and `crud.xsp` render through
  `xp:messages` — a save no longer fails silently.
* Setting a value to `null` removes the item. That is deliberately distinct from storing
  `""`, and it keeps a model that is a view of the document from looking like a shadow copy.

`contact.xsp` binds `category` and `digits` per phone row (`#{eachPhone.category}`,
`#{eachPhone.digits}`). Those keys *are* the stored field names — see
[Adding a structured type](#adding-a-structured-type).

## Storage

`AbstractDocumentMapModel.replaceItemValue` first tries a native Domino item, then a codec,
and only then the legacy Java-serialization path:

| Value | Stored as |
|---|---|
| `null` | the item is removed |
| `String` (≤ 60,000 chars), numbers (`Integer`/`Double` kept, others widened to `Double`), `Boolean` (as `"1"`/`"0"`), `Date`/`Calendar` (as `DateTime`) | native item |
| `Collection`/array of one convertible type, ≤ 255 values, ≤ 60,000 chars of text | native multi-value item; above 14,000 chars it is marked non-summary |
| anything else a codec accepts (`Structured`, `Map`, `Iterable`, `Object[]`) | MIME part carrying the codec payload |
| a `Serializable` value no codec accepts (or a `DocumentCollection`/`NoteCollection`) | legacy Java serialization, kept only for compatibility |

Native limits come from Domino: 255 values per item, a 32 KB summary limit, and hard
failures for mixed value types and oversized text. Those `IllegalArgumentException`s are
what route a value into the codec.

### The `msgpack/1` format

A codec-stored item is a MIME part whose body is MessagePack — self-describing binary
data, like binary JSON:

```
Content-Type: application/x-msgpack
X-Storage-Scheme: msgpack/1
X-Original-Java-Class: java.util.ArrayList        (diagnosis only, never instantiated)
```

The bytes are written and read by `MessagePackCodec` itself — a few hundred lines of
`DataOutputStream`/`DataInputStream`, no library, no reflection, no `Unsafe`. The format is
the same one the previous `msgpack-core` build produced, so items written by it stay readable.

The payload can contain nil, booleans, integers (read back as `Long`), floats (read back
as `Double`), strings, binary, arrays (as `List`) and maps with string keys (as
`Map<String,Object>`). Two structures are reserved:

| Payload | Becomes |
|---|---|
| `{"@type": "date", "@value": <epoch millis>}` | `java.util.Date` |
| `{"@type": "<registered name>", ...fields}` | the object that name's factory builds |

`@type` is reserved and cannot be used as a field name. Values are refused rather than
stored lossily: `BigDecimal`, `BigInteger`, `java.time` types and maps with non-string keys
throw `IllegalArgumentException` naming the field, and a payload larger than
**1 MiB** (`MessagePackCodec.MAX_PAYLOAD_BYTES`, checked on write *and* on read) is an
`IOException`.

Reading dispatches on the stored **scheme**, never on a type name in the payload:

* unknown scheme → `IllegalArgumentException`; there is no silent fallback, because a
  document that cannot be read must not quietly turn into an empty field;
* truncated or corrupt payload, or trailing bytes after the first value →
  `IOException`;
* a `@type` that is not registered → `IllegalArgumentException` naming the type.

The point of the format is the read path: MessagePack decoding can only produce maps,
lists, strings and scalars. Nothing in the bytes makes the reader load a class, call a
constructor or run `readObject`, so a hostile document can at worst produce a wrong map and
an exception. Objects are rebuilt afterwards, and only through the
[`StructuredTypes`](Code/Java/org/openntf/xsp/util/codec/StructuredTypes.java) allow-list
the application filled in.

Failures are loud. A stored value that cannot be decoded (corrupt payload, unknown scheme,
unregistered type) marks the document as partially loaded, the page shows that, and `save()`
refuses to write it back instead of replacing the unreadable item with a default. If a codec
accepts a value but fails to encode it, the save fails as well; there is no fallback to Java
serialization for a value a codec was chosen for, and a failed item write aborts the save
before `Document.save()` runs.

### Legacy documents

Values written by the original demo are Java-serialized MIME parts
(`application/x-java-serialized-object`, `application/x-java-externalized-object`, or the
`X-Storage-Scheme: StateHolder` form, plus `DocumentCollection`/`NoteCollection` rebuilt
from UNIDs). `DominoUtil.restoreState` still reads them; a compressed payload is capped at
4 MiB after expansion (`DominoUtil.MAX_STORED_BYTES`), so existing contacts keep working.

Nothing writes new Java-serialized values unless no codec can encode the value (or encoding
fails); that fallback is traced at debug level and can go once no documents from the old
format remain. Codec payloads are not compressed — the gzip path exists for legacy parts.

## Adding a structured type

A class that should be stored as data rather than as bytes implements `Structured`:

```java
public final class Note implements Structured {
    public static final String TYPE_NAME = "howyabean/note";

    private final Map<String, Object> fields = new LinkedHashMap<>();

    @Override
    public String type() {
        return TYPE_NAME;
    }

    @Override
    public Map<String, Object> toMap() {
        return fields; // keys are the wire format
    }

    /** The only way a stored note comes back: the decoder calls this, never a constructor. */
    public static Note fromMap(final Map<String, Object> values) {
        Note result = new Note();
        result.fields.putAll(values);
        return result;
    }
}
```

Register the factory once, in the class that declares the shape rather than in the stored
class: the registry has to be filled before the first stored value is decoded, and a design
refresh that reloads the model class replaces the factory together with the class loader.

```java
static {
    StructuredTypes.register(Note.TYPE_NAME, Note::fromMap);
}
```

`ContactModel` does exactly this for `PhoneNumber` (`TYPE_NAME`,
`PhoneNumber.fromMap`). Rules that matter:

* **Keep `type()` and the map keys stable.** Renaming either makes existing documents
  unreadable; both are a data migration, not a refactor. The demo uses a fully qualified
  class name as the type name, which is only a naming convention — the name is data.
* **Only storable values in `toMap()`**: `null`, strings, `Boolean`, `Byte`…`Double`,
  `Date`, `byte[]`, maps with string keys, collections, or further `Structured` values.
* **Register before the first read.** A design refresh reloads the classes and re-runs the
  static block, replacing the old factory; the registry is static state and goes with the
  old class loader.
* A `@type` name nobody registered is a hard failure, by design. That is the trade for not
  trusting document content.

## Resource scopes

Domino handles (`Document`, `Item`, `MIMEEntity`, `Stream`, …) must be recycled, and
`Session.isConvertMIME()` must be restored on every exit path. Both are `AutoCloseable`
scopes instead of `finally` blocks:

```java
try (MimeScope mime = MimeScope.conversionOff(session); Recycle parts = Recycle.of()) {
    MIMEEntity entity = parts.own(doc.getMIMEEntity(itemName));
    Stream stream = parts.own(session.createStream());
    ...
}
```

Rules:

* **`MimeScope` is declared first** so it closes last: the parts are recycled while MIME
  conversion is still off, and only then is the previous session setting restored.
* `Recycle` releases handles in reverse acquisition order (LIFO), so children go before the
  parents they came from.
* `own(...)` records the handle and returns it, which is what makes acquisition and
  ownership one expression. `of(...)`/`add(...)` take handles or a collection
  (`doc.getItems()`).
* `close()` is idempotent and best effort: a failing `recycle()` is traced, never thrown,
  so it cannot mask the exception already leaving the block. Calling `add` after `close`
  throws `IllegalStateException`.
* Use one scope per method, not per value. Scopes nest: a helper opening its own `MimeScope`
  restores the outer state, not the caller's original one.

## The smart model

`AbstractSmartDocumentModel` decides per key whether the bean or the document map answers.
Whether a key is a bean property is a property of the **class**, so it is resolved once per
class through `java.beans.Introspector` and cached in a static `ConcurrentHashMap`
(`computeIfAbsent`), not looked up with reflection on every access and not signalled by an
exception.

Consequences worth knowing:

* A document item is a map miss, not a thrown `NoSuchMethodException`; a page render costs
  one hash lookup per attribute.
* A bean hit costs one `Method.invoke`. If the getter or setter itself fails, the failure
  is traced (with the `InvocationTargetException` unwrapped) and the map is used instead,
  so the page stays alive.
* `getType` returns the property type where there is one, otherwise the stored value's
  class. `isReadOnly` is `true` outside edit mode; in edit mode a key with no setter is
  read-only too.
* The accessor cache is static on purpose: the model is view scoped and serialized by JSF,
  and neither `Method` nor `PropertyDescriptor` is serializable. The cache is released
  together with the class loader on a design refresh.

## Building

There is no build file, no CI and no test in this repository. The way it runs is: open the
ODP in Domino Designer, sync it into an NSF (same replica id — see
[REVIEW.md §2](REVIEW.md)), and open `index.xsp`.

Details that bite:

* `.classpath` is Designer-generated and is **not** a plain Eclipse build: `Code/Java` is
  not a classpath entry of its own and `Local` is a Designer-internal virtual folder.
* The repository ships no third-party jar. If one is ever added, it belongs in `Code/Jars/`
  as a jar design element (not as a plain file in `WebContent/WEB-INF/lib/`), and it must
  **not** also be declared as a `lib` entry in `.classpath`: the design element already
  contributes `WebContent/WEB-INF/lib/<name>.jar` to the build path, and the second entry
  shows up as *Build path contains duplicate entry*.
* `WebContent/WEB-INF/classes` is the compiler output directory (`.classpath`); it is not
  in the repository and should not be committed.
* The compiler level is versioned in `.settings/org.eclipse.jdt.core.prefs`
  (`compliance`/`source`/`codegen.targetPlatform` = 21), matching the JVM Designer 14.5 runs
  on (openjdk 21.0.9). A lower level while Designer uses JRE 21 produces *The compiler
  compliance specified is X but a JRE Y is used*; the level also sets the class-file
  version, so 21 means the NSF needs a Domino 14.5+ server.
* A headless build is possible with the `org.openntf.nsfodp` Maven plugin; alternatively
  compile `Code/Java` against the Domino Java API jars from the Designer installation.
  Both are suggested in [REVIEW.md §5](REVIEW.md), neither is committed.
* The changes to the model, the codec and the scopes were verified with a local `javac`
  compile against small `lotus.domino`/`com.ibm.xsp` stubs plus a codec round-trip probe
  (structured values, lists, dates, `byte[]`, nested maps; unknown type, `@type` collision,
  trailing bytes and oversized payloads refused). That harness is not part of the repo, and
  a Designer/`nsfodp` build remains the real gate.

## Troubleshooting

No Java policy grant is needed. The former `msgpack-core` jar reached for `sun.misc.Unsafe`
through `setAccessible`, which the Notes/Designer and Domino security managers refuse with
`ReflectPermission "suppressAccessChecks"`; since the codec implements the format itself, that
dependency — and the permission — are gone.

`AppProperties/database.properties` is rewritten by Designer on every sync (replica id, ODS,
ACL log, icon element) — read it as a description of the target database, not as a file to
hand-edit.

## Known limitations

* The phone book is **one opaque MIME part**. Views, full-text search, agents and conflict
  handling cannot see individual numbers, and two editors overwrite each other's rows.
  Making it viewable (parallel `phoneCategory`/`phoneDigits` items) stays inside the model's
  item mapping, because the storage format is behind the codec seam.
* No field validation and no conflict detection: `save()` is last-write-wins.
* The documentation set is new. `README.md` covers architecture, storage and build;
  `REVIEW.md` still lists the operational gaps that matter before this is used for real
  data — the permissive ACL (`-Default-` = Manager), the hard-coded replica id, `fileex`
  session persistence in a cluster, the shared `org.openntf.xsp.*` namespace, and the
  `plugin.xml` reference to a missing `plugin.Activator`.

## License

[Apache License 2.0](LICENSE). The repository is a derivative of Tim Tripcony's OpenNTF
demo; the copyright notice names the original author and OpenNTF.

## Documentation map

| Document | Purpose |
|---|---|
| [README.md](README.md) | How it works: model, storage format, codec, scopes, build. |
| [REVIEW.md](REVIEW.md) | Architecture and Java 8 review, findings by severity, prioritised roadmap, change notes. |
