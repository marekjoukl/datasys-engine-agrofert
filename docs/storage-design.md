# Storage Design

## 0. Scope and constraints

Constraints we are designing under:

- Only three column types: `STRING`, `LONG`, `DOUBLE`.
- No external storage or file-format libraries for the _data_ format. A JSON /
  properties library for the catalog is permitted.
- CSV input is headerless, comma-separated, ASCII, with no quoted fields.
- One `copyFile` per table in Part 1; appends arrive in Part 2.
- `select` must prune partitions using min/max summaries without reading the
  column data those summaries describe.
- Part 2 will add deletes and copy-on-write over immutable partitions, plus a
  versioned catalog and concurrent writers. Decisions below should not make
  that harder than necessary.

---

## 1. Catalog storage

**Decision:**

One JSON file per table.

**On-disk layout:**

```
<dataDirectory>/
├── table1/
│   ├── catalog.json
│   └── table1.data
└── table2/
    ├── catalog.json
    └── table2.data
```

One file per table - this is more scalable solution than one catalog file for the DB. It also makes concurrent access easier.

JSON - it makes reading and debugging easier, which might be useful during the course.

---

## 2. Catalog contents

**Decision:** Per table, the catalog stores:

    - `name` - table name
    - `columns` - ordered list of `{name, type}`
    - `files` — list of data files belonging to the table
    - `partitions` - list in on-disk order, per partition:
        - `file` - which data file it lives in
        - `offset` - byte offset of the partition within that file
        - `rowCount`
        - `summaries` - `{min, max}` values per column
        - `chunks` - per column `{offset, length}`; PAX cannot locate a chunk without them

---

## 3. Where the min/max summaries live

**Decision:**

Catalog only - we sacrifice self-description to get zero I/O on pruning.

---

## 4. Restart behaviour

**Decision:** eager directory scan for table names, lazy parse of each table's metadata

Fast startup; unused tables are never parsed.

---

## 5. Layout inside a partition

**Decision:**

PAX

Scans read only the predicate column's chunk, not whole rows.

---

## 6. Partition size

**Decision:**

1024 - for now, might be updated later based on performance

Smaller partitions prune better but cost more metadata.

Tests need small values, so `StorageEngine(Path, int maxRowsPerPartition)`
overloads the specified constructor, which delegates with the default.

---

## 7. Value encodings and framing

**Decision:**

| Type     | Encoding                             |
| -------- | ------------------------------------ |
| `LONG`   | 8-byte two's-complement              |
| `DOUBLE` | 8-byte IEEE 754                      |
| `STRING` | 4-byte length-prefixed + ASCII bytes |

Fixed-width numbers make any row easy to find; strings carry their length so we
can skip them.

**File framing:** files start with magic bytes `AGRO` and a 2-byte version, so a
wrong or old file fails at once.

**Finding a column chunk:** from the catalog's `chunks` entry (§2) - no
data-file reads needed.

---

## 8. Byte order

**Decision:**

little-endian

Matches the hardware, and is set explicitly rather than relying on
`ByteBuffer`'s big-endian default.
