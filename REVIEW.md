# How Ya Bean? — Architecture & Java-8 Review

Reviewed as an **NSF on-disk project (ODP)** for IBM/HCL Domino (XPages).
Repo contains design elements only (no documents), single commit `45b02eb "Initial Commit"`.

---

## 1. What this project is

Tim Tripcony's "How Ya Bean?" OpenNTF demo: a CRUD app for contacts that shows a
**hand-rolled document model** (bean/`DataObject` hybrid) driven from XPages EL, instead of the
built-in `xp:dominoDocument` data source.

### Element map

| Area | Files |
|---|---|
| Pages | `XPages/index.xsp` (list + pager), `XPages/contact.xsp` (detail/editor), `XPages/crud.xsp` (list+detail combined) |
| Custom control | `CustomControls/navbar.xsp` (+ `.xsp-config` composite component `navbar`, prefix `xc`) |
| Java | `Code/Java/org/openntf/howyabean/model/{ContactModel,PhoneNumber}.java`, `Code/Java/org/openntf/xsp/model/{AbstractMapModel,AbstractDocumentMapModel,AbstractSmartDocumentModel}.java`, `Code/Java/org/openntf/xsp/util/{DominoUtil,LogUtil}.java` |
| Data | `Forms/contact.form`, `Views/contacts.view` (selection `Form = "contact"`, 2 columns, programmatic names `lastName` + `firstName`, collation lastName/firstName + `$Conflict`, `$REF`) |
| Config | `WebContent/WEB-INF/faces-config.xml` (bean `contact`, view scope), `xsp.properties`, `plugin.xml`, `Resources/Themes/howyabean.theme` (extends `bootswatch_united`), `Resources/StyleSheets/howyabean.css` |
| Libs | ~~`commons-beanutils-1.8.3.jar`, `commons-collections-3.2.1.jar`~~ — removed, see P1 #7 |

### Design / call flow

```
contact.xsp  ──#EL──►  ContactModel (view scope, faces-config:5-9)
                          └ extends AbstractSmartDocumentModel  ← JavaBean properties via
                            extends AbstractDocumentMapModel      commons-beanutils reflection,
                            extends AbstractMapModel              falling back to a
                              implements Serializable, DataObject Map<Object,Object> of items
                                                                    │
                     querySave()/save() ──► DominoUtil.saveState / replaceItemValue ──► NotesDocument
                     load(unid)          ◄── DominoDocument.wrap + MIMEBean restore
```

Layer responsibilities:

* **`AbstractMapModel`** — `DataObject` over a lazy `HashMap<Object,Object>`. `DataObject` is XPages' contract for EL-addressable values (`getValue`/`setValue`/`getType`/`isReadOnly`).
* **`AbstractDocumentMapModel`** — maps items ⇄ document: `load()`, `save()`, `querySave()`/`postSave()` hooks, native vs. MIMEBean ("java serialized") storage (`AbstractDocumentMapModel.java:131-274`, `276-303`).
* **`AbstractSmartDocumentModel`** — lets real bean properties win over map entries. This is the interesting idea: typed properties where you have them, arbitrary items everywhere else. (Originally via `PropertyUtils` with a `catch (Throwable)` fallback; since the P1 #7 change it is a per-class `Introspector` accessor cache, so a document item is a map miss rather than a caught exception.)
* **`ContactModel`** — form name, `phoneBook` (`List<PhoneNumber>`), edit mode.
* **`PhoneNumber`** — one repeat row; `isReadOnly` delegates to `ContactModel.isEditMode()`.
* **`org.openntf.xsp.util.Recycle` / `MimeScope`** — `AutoCloseable` scopes for Domino handles and for the `Session.isConvertMIME()` flag, so a block declares what it takes and never writes a `finally` for it. Declared left to right in one `try (...)`, closed right to left: MIME parts are recycled while conversion is off, then the session is restored.
* **`org.openntf.xsp.util.codec.*`** — the storage seam: `ValueCodec` (scheme, content type, `encode`/`decode`), `ValueCodecs` (registry plus `forValue`/`encode`/`decode`), `MessagePackCodec` (`msgpack/1`), and `Structured`/`StructuredTypes`/`StructuredValues` for values that need their type back. A value Domino cannot hold natively is written as MessagePack bytes with an `X-Storage-Scheme` header instead of a Java-serialized MIMEBean.

### Verdict on the architecture

The layering is clean and the "smart bean" idea is genuinely nice, but in practice it
**re-implements `DominoDocument`** while adding two structural weaknesses:

1. `ContactModel` declares **no properties at all** — `firstName`, `lastName`, `email`, `employer`, `jobTitle`, `birthDate` exist only in the map. So every single EL read/write takes the *exception path* in `AbstractSmartDocumentModel` (`getValue` → `PropertyUtils.getProperty` throws `NoSuchMethodException` → `super.getValue`). For `contact.xsp` that is ~20+ thrown-and-filled stack traces per page render (reads + `isReadOnly` + `getType`), i.e. pure overhead with no benefit. Either declare the properties or drop `AbstractSmartDocumentModel`. *Partly addressed:* the exception path is gone (P1 #7), so the mismatch now costs a hash lookup instead of ~20+ throws per render.
2. The nested phone book is persisted as a **MIME part** (`getPhoneBook()` is not Domino-convertible → `IllegalArgumentException` → the codec branch), so it is still invisible to views, search, full-text index, agents and conflict handling. What changed (P1 #6): the bytes are MessagePack and the type is a name in the payload (`{"@type": "…PhoneNumber", "category": …, "digits": …}`), not a class name the reader instantiates through `Class.forName(...).newInstance()`. Stored values are rebuilt only through a factory registered in code, so the read path cannot be steered by document content.

---

## 2. Domino-environment specifics (things that bite in an ODP)

1. **ODP sync contract.** `.project` uses `com.ibm.designer.domino.team.builder.PhysicalToNsfSynBuilder`: the repo is synchronised into an NSF. Design-element identity is carried by the `*.metadata` companions (noteid/unid/sequence) — *never* add/delete/regenerate these, or Designer creates duplicates / breaks references.
2. **Hard-coded replica id.** Every element and `AppProperties/database.properties:3` pins `replicaid='85257C3A00267A39'`. This ODP can only be synced to a DB with that exact replica id — it cannot be deployed to a fresh DB or another environment without editing every metadata file. For real deployment, publish to a **template (.ntf)** and use design replace/inheritance.
3. **ACL is versioned, therefore pushed on sync.** `AppProperties/database.properties:9-15`: `-Default-` is **Manager with delete-documents**, `maxinternetaccess='editor'`, no `Anonymous` entry. Anyone reaching the HTTP task can read, edit and delete every document. Fix before this repo is ever deployed anywhere (add `Anonymous` = No Access, `-Default-` = Author/Reader, use roles).
4. **`.classpath` is Designer-generated.** `Local` (`\\.classpath:3`) is a virtual folder used internally by DDE; the real sources live in `Code/Java`, which is *not* a classpath entry. Consequence: no plain Eclipse/JDT/VSCode/javac build works from this repo — you need DDE, or a headless build (see §5).
5. ✅ **Compiler settings are now versioned.** `.settings/org.eclipse.jdt.core.prefs` (`compliance`/`source`/`codegen.targetPlatform` = 1.8) fixes the level in the repo; previously it came from DDE global preferences. That is exactly the "compliance level is fiddly / silently 1.5–1.6" trap in Domino, and it blocks adoption of Java 8 syntax. Create the file with `org.eclipse.jdt.core.compiler.source=1.8`, `...codeGenTargetPlatform=1.8`, `...compliance=1.8`.
6. **`plugin.xml:3` declares `class="plugin.Activator"`, but no `plugin/Activator.java` exists** in the project. Harmless while the ODP is only synced into an NSF, fatal if it is ever built/exported as an OSGi library. Also the plugin id `"howyabean ODP"` (`plugin.xml:2`) contains a space — invalid by Eclipse/OSGi convention.
7. **`xsp.persistence.mode=fileex`** (`WebContent/WEB-INF/xsp.properties:3`) keeps session/view-scope state in a JVM-local directory. Fine on one server, silently broken in a Domino cluster or behind a load balancer (view-scope bean → `Unable to load ... state`/new bean per request on the other server). Use `dominodatabase` for clustered deployments.
8. **Package namespace collision.** Utility classes live in `org.openntf.xsp.*` (`DominoUtil`, `LogUtil`, `Abstract*Model`), a namespace shared with other OpenNTF artifacts. Rename to `org.openntf.howyabean.*` before anyone copies them into another DB or plugin.
9. **Third-party jars shipped into WEB-INF/lib.** `commons-beanutils 1.8.3` (2010, EOL) and `commons-collections 3.2.1` (EOL; the classic `InvokerTransformer` deserialization gadget chain, CVE-2015-6420/CVE-2015-6420-family). Both jars used to land in the NSF and on the app classloader for no net gain — beanutils was used only for `PropertyUtils` in one class, collections only as a transitive dependency. **Both dropped** (P1 #7): `AbstractSmartDocumentModel` now uses `java.beans.Introspector`, so the two `lib` entries are gone from `.classpath` and the jars from `WebContent/WEB-INF/lib`. One dependency is added: `msgpack-core 0.9.8` (Apache 2.0, active, no serialization-gadget surface). It is 123 KB, but note that it still lands in the NSF — a single-jar cost that buys viewable-by-nothing data with a safe reader; if that trade is not worth a jar, the codec seam allows a pure-Java writer later without touching the call sites.
10. **No README / LICENSE / .gitignore.** `org.openntf`-prefixed code with no license file, no statement of required Domino version, no build instructions. `WebContent/WEB-INF/classes` (the `.classpath` output dir) and `Code/dbscript.lsdb` are also not ignored.

---

## 3. Java 8 audit

### 3.1 The platform question comes first

Java 8 features are only usable where the runtime is Java 8:

| Target | Java level |
|---|---|
| Domino 9.0.1 **FP7 and lower** | Java 6 — no lambdas, no streams, no `java.time` |
| Domino 9.0.1 **FP8+** | Java 8 runtime, but *DDE FP8 ships a 1.6 JRE for compiling* (the 1.8 JRE could not compile) → you cannot compile Java 8 syntax in DDE at FP8 |
| Domino 10 / 11 | Java 8 (OpenJ9 8) — normal Java 8 authoring |
| Domino 12 | Java 11 (12.0.1+ can target 17) |

Also: XPages code compiled by newer DDE does not run on pre-FP8 servers regardless of the "minimum supported release" setting. So the honest answer to "are all Java 8 mechanisms used?" is:

> **Mostly now.** Originally none: the code was Java 5/6 style throughout (explicit type arguments, `new ArrayList<X>()`, no diamond, no try-with-resources, no multi-catch, no lambdas/streams/`Optional`/`java.time`). The compile level is pinned to 1.8 in `.settings/org.eclipse.jdt.core.prefs`, and the following are in use: try-with-resources + `AutoCloseable` (`Recycle`, `MimeScope`), the diamond operator everywhere, multi-catch (`IllegalAccessException | InvocationTargetException`), method references (`AbstractSmartDocumentModel::buildAccessors`) and the Java 8 default method `ConcurrentMap.computeIfAbsent`. Deliberately still absent: lambdas/`Stream` and `Optional`. `lotus.domino` throws checked `NotesException` from almost every method, so a lambda body inside `Stream.forEach` degenerates into try/catch and reads worse than the loop it replaces; `Optional` around the nullable-header contract would just move the `null` one call out. `java.time` is the next honest win (it needs a conversion shim at the Domino boundary, not scattered `Date.from(...)` calls). The design elements are stamped `$DesignerVersion 8.5.3` and the ODS is 43 (9.0), i.e. the project predates Java 8 on Domino by ~4 years. Before modernising: pin the target (Domino ≥ 9.0.1 FP10 / V10+), add `.settings/org.eclipse.jdt.core.prefs` at 1.8, and make sure every replica runs that level.

### 3.2 Feature-by-feature

| Java 8 mechanism | Used | Where it would pay off (file:line) |
|---|---|---|
| Lambdas / method refs | ✗ | `DominoUtil.incinerate` recursion (`DominoUtil.java:41-73`), item loop (`AbstractDocumentMapModel.java:92-109`), `entrySet` loop (`:288-292`), `removePhoneNumber` (`ContactModel.java:47-49` → `removeIf`) |
| Streams / `Collectors` | ✗ | `doc.getItems()` → `StreamSupport.stream(...)`; `unids[]` building (`AbstractDocumentMapModel.java:219-225`, `233-239`); `String[]`→`List` (`DominoUtil.java:244-250` dead method) |
| `Optional` | ✗ | `getItemValueMIME` and `getType` return `null` on absence (`AbstractDocumentMapModel.java:50-69`, `AbstractSmartDocumentModel.java:27-35`) |
| Default / static interface methods | ✗ | Biggest structural win: turn `AbstractMapModel`/`AbstractDocumentMapModel` into interfaces (`DocumentBacked`, `TypedDataObject`) with default methods so models compose instead of single-inheriting |
| Try-with-resources (7) | ✓ | `Recycle`/`MimeScope` (`org.openntf.xsp.util`), `MessageBufferPacker`/`MessageUnpacker`/`ObjectInputStream` in `DominoUtil` and `codec.MessagePackCodec` |
| Multi-catch (7) | ✗ | `ClassNotFoundException` + reflective failures (`AbstractDocumentMapModel.java:245-266`) |
| Diamond `<>` (7) | ✗ | `new ArrayList<PhoneNumber>()` (`ContactModel.java:27`), `new HashMap<Object,Object>()` (`AbstractMapModel.java:30`) |
| `java.time` | ✗ | No `LocalDate`/`Instant` support in `toDominoFriendly` (`DominoUtil.java:252-292`); `birthDate` is `java.util.Date` end-to-end |
| `java.util.Objects` | ✗ | null-safety in `setValue`/`getValue`, `requireNonNull` on constructors |
| `Map` defaults (`getOrDefault`, `computeIfAbsent`, `forEach`) | ✗ | `getPhoneBook()` lazy init (`ContactModel.java:25-30`), `containsKey`+`get` double lookup (`ContactModel.java:36-37`), `getValues().entrySet()` loop |
| `String.join` / `Collectors.joining` | ✗ | `LogUtil.debug` string concat (`LogUtil.java:15`), log messages (`AbstractDocumentMapModel.java:204-205`) |
| `String` switch | n/a | nothing to switch on |
| `java.util.Base64` | n/a | no manual base64 |
| `CompletableFuture` / `parallelStream` | ✗ (correctly) | **Do not** introduce: `lotus.domino` objects are session/thread-bound and not safe to share across threads |
| `Comparator.comparing` | n/a | no sorting in code (view does it) |
| `@FunctionalInterface` + `Function` registry | ✓ | `StructuredTypes.register(String, Function<Map<String,Object>,?>)` — the name→factory registry that replaces the old `Class.forName(...).newInstance()`; `toDominoFriendly`'s if/else chain (`DominoUtil.java:257-291`) could use the same shape |
| `MethodHandle`/`Introspector` | ~ | `java.beans.Introspector` now in use in `AbstractSmartDocumentModel.java` (both jars and the exception hot path are gone); still `Method.invoke`, not `MethodHandle` |
| `Class.newInstance()` | used (deprecated in 9+) | `DominoUtil.java:116,129` → `getDeclaredConstructor().newInstance()` |

---

## 4. Findings by severity

### A. Correctness bugs

1. **`birthDate` date pattern is wrong**: `pattern="yyyy-mm-dd"` — `mm` is **minutes**, not month. `contact.xsp:45`, `crud.xsp:92` → `MM`.
2. **`setConvertMIME` never restored on the error path** — in both `AbstractDocumentMapModel.getItemValueMIME` (`:50-69`) and `DominoUtil.restoreState` (`:88-161`, early `return null` at `:96`) the save/restore of `session.isConvertMIME()` (`:90-91`, `:174-175`) sits inside a `try` with no `finally`. Any exception (e.g. `entity == null` → NPE at `:57`) leaves MIME conversion disabled for the rest of the JVM-wide/long-lived session object → later code silently sees rich text instead of MIME. Fix with try/finally.
3. **The committed Java does not compile against the Domino Java API** — confirmed, not suspected: compiling the tree against JDK 8 plus stubs of the API (§5) reported exactly two errors, both in the original code — (a) the property is `Session.isConvertMIME()` / `setConvertMIME(boolean)` (all-caps `MIME`), but `DominoUtil.java:90,91,159,174,175,241` used `isConvertMime`/`setConvertMime`; (b) `MIMEEntity.remove()` (`AbstractDocumentMapModel.java:179,197`) does not exist — `MIMEEntity` extends only `Base`; the way to drop a MIME item is `Document.removeItem(String)`. Both are fixed. The lesson is the finding: the on-disk source had never been compiled, so the first thing this repo needs is a build (§5).
4. **Non-MIMEBean MIME items are nulled and then overwritten with `""`** — `getItemValueMIME` returns `null` for any MIME entity whose Content-Type is not a java-serialized/externalized type (`:57-63`), `load()` still stores that `null` (`:100-102`), and `toDominoFriendly(null)` maps `null` → `""` (`DominoUtil.java:257-259`). Save = data loss on any MIME item the model doesn't understand.
5. **Summary-flag heuristic only covers list values** — `isNonSummary` is computed in the `Collection`/`Object[]` branch (`:160-169`, `185-187`) but not in the scalar branch (`:189-207`), so a single string >14 KB is still written as a summary item (32 KB summary limit).
6. **Clearing a list stores a serialized blob instead of removing the item** — empty `Collection` throws `IllegalArgumentException` on purpose (`:181-183`), falls into the serialization branch (`:209-213`), and writes a MIMEBean containing an empty list.
7. **`save()` failures are invisible** — `catch (Throwable)` + `printStackTrace` (`:296-297`) while the XPages action `#{contact.save}` (`contact.xsp:86`) ignores the boolean. The page re-renders from the in-memory bean, so it *looks* saved. Add `FacesMessage` + return `false`/log.
8. **Stale/unknown `?id=` is silently swallowed** — `doc` is `null` when the UNID doesn't resolve, `doc.getParentDatabase()` NPEs (`:84`), caught at `:111-112`.
9. **`ContactModel.load` can `ClassCastException`** — `(List<PhoneNumber>) getValues().get("phoneBook")` (`ContactModel.java:36-38`) assumes the item is always a MIMEBean list.
10. **`removePhoneNumber` resolves a repeat variable reflectively** (`ContactModel.java:47-49`) — a `null` from `ExtLibUtil.resolveVariable` silently removes nothing; pass the row via `xp:parameter` instead.

### B. Resource / performance

11. **Domino object leaks on every load/save**: `getItemValueMIME` never recycles `MIMEEntity`/`MIMEHeader` (`:56-57`); `DominoDocument.wrap(...)` (`:84-91`) is never recycled; `doc.getItems()` vector isn't recycled (individual `Item`s are); `getMIMEEntity` in `replaceItemValue` (`:177`, `:195`, `:191`) is never recycled. In XPages these accumulate until session GC — classic "Unable to load page / out of memory" territory.
12. **`ArrayListUtil.stringToArrayList(...)` is rebuilt inside the item loop** (`:97-98`), on `com.ibm.jscript.debug.ArrayListUtil` — an *internal, non-API* class from the JavaScript interpreter bundle, and an `ArrayList.contains` (O(n)) per item. Hoist to a `static final Set<String>`; drop the internal dependency.
13. **`AbstractSmartDocumentModel` performance**: every read miss throws (see §1), and `isReadOnly` runs *two* reflective lookups per check (`:61`) and `getType` another (`:30`). Cache `PropertyDescriptor`s / `MethodHandle`s once per class.
14. **`LogUtil.debug` allocates a `Throwable` just to get the caller** (`LogUtil.java:9`); `DEBUG_ENABLED`/`TRACE_ENABLED` are `static final true` (`:4-5`) so nothing is compiled out. Use `java.util.logging` (routes to `IBM_TECHNICAL_SUPPORT/xpages_*.log`) with a level check; `printStackTrace` to stdout (`:21`, `AbstractDocumentMapModel.java:112,297`, `DominoUtil.java:144,213`) never reaches the Domino log properly.
15. **`contact.xsp:3-5` declares an `xp:dominoView` data source that is never used** on that page (the detail page needs no view) — a full view open per render for nothing.
16. `index.xsp`/`crud.xsp` duplicate the same list markup (~30 lines each) — extract a custom control (`beans.xsp` style), the project already demonstrates the pattern with `navbar`.

### C. Robustness / security

17. **`catch (Throwable)` everywhere** (12 sites) swallows `OutOfMemoryError`, `StackOverflowError`, `ThreadDeath` and hides real defects. Restrict to the checked types, or at least rethrow `Error`.
18. **Empty catch** `catch (Exception e) {}` on `recycle()` (`DominoUtil.java:48`) — silent, but at least log at debug.
19. **Bare `throw new IllegalArgumentException()`** with no message (`:139,143,157,168,182`) — no way to diagnose which value/item failed; unused variable `noteid_` (`:135`) exists only for a log line.
20. **Java deserialization as storage** — `DominoUtil.restoreState` does `Class.forName(header).newInstance()` and `ObjectInputStream.readObject` on document data (`:104-120`), i.e. anyone who can write a document (ACL `-Default-` = Manager today!) can attempt a gadget chain. Java serialization in DOMI/NSF storage should be treated as trusted-input-only; a JSON/Jackson or delimited multi-value representation is the safer design.
21. **`toDominoFriendly` returns a live `Item` unchanged** (`:260-262`) — "already safe to store" is not true; handing a recycled/invalid `Item` to `replaceItemValue` throws on later use.
22. **No validation, no conflict detection.** `querySave` only writes `phoneBook`; no required-field checks, and `save()` uses `doc.getDocumentByUNID(...)` + `doc.save()` (last-write-wins). XPages' `DominoDocument` supports `concurrencyMode="force"`/`fail"`; here you'd need `doc.save(true, false)` + `$Conflict` handling.
23. **`MAX_NATIVE_VECTOR_SIZE` is a public mutable static** (`:33`) — should be `static final`.

### D. Code quality / dead code

24. `DominoUtil.toDominoFriendly(Collection, Document)` (`:244-250`) is `protected static` and unused (and its `context` parameter is ignored).
25. `AbstractSmartDocumentModel.getTypedValue` (`:37-39`) is unused.
26. `PhoneNumber implements DataObject` (`PhoneNumber.java:10`) is redundant — `AbstractMapModel` already implements it.
27. `@Override` missing on `AbstractMapModel.getType/getValue/setValue/isReadOnly` (`:13,24,35,39`) — inconsistent with the subclasses, loses compile-time protection.
28. `PhoneNumber.contact` is `transient` but resolved from EL each first use (`:14-19`) — NPE if the `contact` bean is absent (e.g. the list where `PhoneNumber` is deserialized outside that page).
29. `// valueClass = (listValue).get(0).getClass();` (`:176`) — commented-out dead code; `/* WARNING: Nathan says this bit might still be buggy... */` (`DominoUtil.java:132-134`) — a known-buggy path shipped in the read path.
30. Inconsistent `serialVersionUID` placement/style, tabs/brace noise, no package-level docs.

---

## 5. Build & testability

There is no build, no CI, no test. Because `Code/Java` is outside `.classpath`, nothing compiles without DDE.

What does exist now is a **local compile check**: JDK 8 and ~15 tiny stubs of `lotus.domino`/`com.ibm.xsp`/`javax.faces` are enough to compile the whole `Code/Java` tree with plain `javac` (`-source 1.8 -target 1.8`), which is what surfaced finding A3. The same setup runs the codec in isolation: a probe class round-trips a `Structured` value, a list of them, dates, `byte[]` and nested maps, and asserts that an unregistered type, an `@type` collision, trailing bytes and an oversized payload are all refused — 17 checks, all green. A phone book of two numbers is 178 bytes as MessagePack against 633 bytes of Java serialization.

* Use **`org.openntf.nsfodp`** (Maven plugin + compiler) for a headless ODP→NSF build and a `compile` goal in CI; it is the maintained answer to "how do I build an ODP without Designer". It will immediately catch §4-A3.
* Alternative/additional: compile the pure-Java parts against the Domino Java API jars installed from the DDE installation into a local Maven repo (`install:install-file`), so IDEs (VSCode/IntelliJ) can resolve `lotus.domino` and you get real compile feedback.
* ✅ Added `.settings/org.eclipse.jdt.core.prefs` (1.8) so compliance is versioned; still missing a `.gitignore` for `WebContent/WEB-INF/classes`, `Code/dbscript.lsdb`, `.DS_Store`, `*.bak`.
* Unit-testable core: `DominoUtil.toDominoFriendly`, `LogUtil`, the model's value/type/read-only resolution — all pure Java if `ExtLibUtil.getCurrentSession()` is isolated behind a small `SessionProvider` seam (also makes it testable *and* thread-cacheable).

---

## 6. Prioritised roadmap

**P0 — fix before any deploy** (≈1 day) — *items 2–5 applied in this working tree; see the change notes at the end of this section*
1. ⚠️ **Accepted for now** (learning demo, no real data): ACL in `AppProperties/database.properties` (`-Default-` Manager → Reader/Author, add `Anonymous`, use roles). Still the first thing to change before this design is reused anywhere real.
2. ✅ `session.setConvertMIME` restore in `finally` (both classes); `yyyy-mm-dd` → `yyyy-MM-dd`; `isConvertMime` → `isConvertMIME` (the committed code did not compile).
3. ✅ Recycle `MIMEEntity`/`MIMEHeader`/`getItems()`; ignore-list hoisted to `static final Set<String> IGNORED_ITEMS`; `com.ibm.jscript.debug.ArrayListUtil` dropped.
4. ✅ Non-MIMEBean MIME items fall back to the wrapped document instead of loading as `null`; `null` now removes the item instead of being written as `""`.
5. ✅ Save failures reachable by the user: `FacesMessage` + `xp:messages` on `contact.xsp`/`crud.xsp`, `save()` returns `false`.

*Change notes:* `Code/Java/org/openntf/xsp/util/DominoUtil.java` (`restoreState`, `saveState`, `toDominoFriendly`, `headerValue`), `Code/Java/org/openntf/xsp/model/AbstractDocumentMapModel.java` (`getItemValueMIME`, `load`, `replaceItemValue`, new `removeMimePart`/`addErrorMessage`, `save`), `XPages/contact.xsp`, `XPages/crud.xsp`, `.classpath` (two `lib` entries removed), `WebContent/WEB-INF/lib/*.jar` (both removed). Later pass, once Java 8 was confirmed as the floor: new `Code/Java/org/openntf/xsp/util/Recycle.java` and `MimeScope.java`, `AbstractSmartDocumentModel.java` rewritten onto a per-class `Introspector` cache, and every `finally`-based recycle/convertMIME restore replaced by a `try (MimeScope …; Recycle …)` statement; `.settings/org.eclipse.jdt.core.prefs` pins the level to 1.8. Recycle remains idempotent and best-effort (a failing `recycle()` is traced, never thrown), because a throw from `close()` would mask the exception already leaving the block. Not covered by a build yet: verified by parser (`javalang`) and by inspection only — no Domino jars on this machine; a Designer/`nsfodp` build is still the real gate.

**P1 — design corrections** (≈2–3 days)
6. ✅ *The serialization surface is gone:* `phoneBook` is stored as a MessagePack MIME part (`X-Storage-Scheme: msgpack/1`) whose payload names the type per value (`{"@type": "org.openntf.howyabean.model.PhoneNumber", …}`); `PhoneNumber implements Structured` and registers `fromMap` for that name, so reading is data-driven and a class name from a document is only ever a registry key. `AbstractDocumentMapModel.replaceItemValue` tries the codec first and keeps the old `ObjectOutputStream` path only to read documents written by the previous version.
   *Still open:* the item is one opaque blob — a view cannot show or sort phone numbers, a full-text index cannot find them, and two editors still overwrite each other's rows. The viewable design (multi-value `phoneCategory`/`phoneDigits`, aligned by position, still one document) is the next step; the codec seam makes that a change in `replaceItemValue` only.
7. ✅ *The reflective part is done:* `AbstractSmartDocumentModel` resolves each class's bean accessors once through `java.beans.Introspector` and caches them in a static `ConcurrentHashMap`, so `getValue`/`getType`/`isReadOnly`/`setValue` are a hash lookup plus (on a bean hit only) one `Method.invoke` — no exception is thrown to signal a document item, and `commons-beanutils` + `commons-collections` are gone (jars, `.classpath` entries).
   *Still open:* either give `ContactModel` real properties (`firstName`, `lastName`, …) so the smart layer earns its keep, or delete it and read the map directly.
8. Move to a template (.ntf) for deployment; stop hand-maintaining the replica id; document the ODP metadata contract in a README.
9. Rename `org.openntf.xsp.*` → `org.openntf.howyabean.*`; fix `plugin.xml` id + missing `plugin.Activator`.

**P2 — modernisation (only after pinning Domino ≥ 9.0.1 FP10 / V10+)**
10. ✅ Compiler level (1.8) versioned in `.settings`; still missing the `nsfodp` Maven build.
11. Adopt Java 8 incrementally in this order: ~~try-with-resources~~ (done: `Recycle`/`MimeScope`), ~~diamond~~, ~~multi-catch~~, ~~method refs~~, ~~`computeIfAbsent`~~ (all done) → `java.time` support (convert at the Domino boundary: `Date.from(ld.atStartOfDay(zone).toInstant()))`).
12. Do **not** parallelise Domino document access with `CompletableFuture`/`parallelStream`; keep `Session`/`Document` on one thread. `CompletableFuture` is fine only for non-Domino work.
13. When adding Java 8 to a view/session-scoped bean, remember JSF serialises it: a lambda field that captures a non-serialisable object breaks view-scope persistence — declare such fields `transient` and re-create them, or keep them `static`.
14. Extract the shared list markup (`index.xsp`/`crud.xsp`) into a custom control, remove the unused view data source on `contact.xsp`, add a delete confirmation + refresh, and switch logging to `java.util.logging`/log4j with a per-class level.

---

### Summary

* **Architecture:** clean 3-layer "smart document model", but it duplicates `DominoDocument` and hides the child collection in one opaque MIME part. The exception per attribute is gone (per-class `Introspector` cache) and the part is now MessagePack with a code-registered type name rather than a class name out of the document.
* **Domino environment:** versioned ACL with `-Default-` = Manager, hard-coded replica id, `fileex` persistence, missing compile settings and a build-less Java tree — the operational risks matter more than the code style.
* **Java 8:** *nothing* is used; the code is Java 5/6-era (`$DesignerVersion 8.5.3`), and the target platform must be decided first — Java 8 language features were only compilable from DDE 10 / Domino 9.0.1 FP8 runtime onwards.
* **Top 5 to fix now:** ACL (accepted as-is for the demo), `setConvertMIME` leak ✅, date pattern `mm`→`MM` ✅, Domino object recycling ✅, `phoneBook` storage format — serialization removed ✅, still one opaque item ⚠️.
