# Hatch — Code Review TODO

_Full-repo review, 2026-10-05, on `develop` @ `f390aa0`._

**Baseline:** `mvn test` passes, 10/10 tests. Items marked **[verified]** were reproduced
with scratch experiments (synthetic slides, CLI runs on throwaway copies, `tifffile` inspection
of the output). All other items come from reading the code; each cites `file:line`.

**Priority legend**
- **P0:** data loss or a corrupt/invalid output file. Fix before the next release.
- **P1:** wrong output or a hard failure on valid input, or a failure that goes unreported.
- **P2:** performance/scalability, robustness, or maintainability with a real cost.
- **P3:** cleanup, build hygiene, docs, conventions.

**Counts:** 6 × P0 · 19 × P1 · 16 × P2 · 43 × P3  
**Done:** all P0, P1 and P2 items, plus the P3 items checked below (several became moot when the reader forks and the old pyramid/writer classes were deleted). Regression tests are in `SafeOutputTest`, `InputHandlingTest`, `CliTest`, `JPEGToolsTest`, `HatchConversionTest`, `PyramidTest`, `TiffTilesTest` and `ValidateTest`.

**Suggested order of work**
1. **Safety PR (all P0).** Path guards (same file, nested dest, name collisions), write to a temp file and atomically move, an `X2TIF` factory that fails cleanly, and the depth == 1 IFD terminator.
2. **Input gate PR.** One up-front check on the IFD/series actually being read: JPEG, 8-bit RGB, tiled, chunky, and a tile grid taken from that same IFD. Also replace `throw new Error`, propagate per-job failures, and use non-zero exit codes. Together these turn most of P1's "garbage output or late crash" items into clear errors.
3. **Metadata PR.** mm/pixel units, best-effort SVS/VSI metadata, omitting resolution tags when unknown, and copying photometric/subsampling from the source IFD.
4. **Performance PR.** Write each IFD once per level, encode each pyramid level once, and make per-tile reads O(1) in both readers.
5. **De-fork.** Replace the TIFF/SVS reader forks with thin upstream subclasses *before* fixing their internals. That makes most of the "Forked Bioformats readers" items in P3, and two of the P2 items, moot.

---

## P0: Data loss and corrupt output

- [x] **Single-file mode deletes the destination unconditionally, which can be the source itself.** **[verified]**
  `Hatch.java:129-131`. If `-dest` exists and is a file, `params.dest.delete()` runs with no `-o` check.
  It also runs *before* the `series.size()>1` usage check, so a usage error still costs you the file.
  `hatch -src a.tif -dest a.tif` deleted `a.tif`, logged `ArithmeticException: / by zero`, and exited 0.
  *Fix:* honor `-o`. Refuse when `src` and `dest` resolve to the same file (`Files.isSameFile`/`toRealPath`).
  Only delete after a successful conversion (see the atomic-write item below).

- [x] **Batch mode can delete source files when `dest` == `src` (or `dest` is inside `src`).** **[verified]**
  `Hatch.java:62-67`. A `.tif` input maps onto the same relative path in `dest`.
  With `-o`, `Traverse` deletes `t` (the source) before the job is even submitted.
  With `-r`, `FileProcessor` deletes any "invalid" existing dest (`Hatch.java:212-215`), and here that is the source.
  `hatch -src batch -dest batch -o` deleted every input and exited 0.
  *Fix:* reject a `dest` equal to or nested under `src`, skip any job whose output path equals its input path, and never delete before success.

- [x] **Output-name collisions are written concurrently.** `Hatch.java:62-64`.
  `a.svs`, `a.vsi` and `a.tif` in one folder all map to `a.tif`. With `-fp > 1` two jobs write the same file at the same time.
  *Fix:* detect collisions up front (fail, or suffix with the source extension).

- [x] **Failed conversions leave partial, invalid TIFFs that later runs silently skip.** **[verified]**
  `X2TIF` writes straight to `dest` (`X2TIF.java:107`) and nothing removes it on failure.
  A run with `-q 1.5` failed after level 0 and left a 103 KB file whose IFD chain points past EOF.
  The next batch run without `-o`/`-r` sees `dest.exists()` and skips it (`Hatch.java:208-221`).
  *Fix:* write to `dest + ".part"` and `Files.move(..., ATOMIC_MOVE)` on success. Delete the temp file on any failure.

- [x] **The full-res IFD's next-IFD pointer dangles when depth == 1.** **[verified]**
  `X2TIF.java:413` always passes `last=false` for level-0 tiles. When `depth == 1` no later level clears it.
  This happens when the image is ≤ half a tile, e.g. picking a label/macro image with `-s`.
  For a 100×100 image the pointer is `21709` and the file size is `21709`; tifffile reports `invalid page offset`.
  *Fix:* pass `last = (depth == 1 && lastTile)`, or patch the final IFD's next-offset to 0 at close.

- [x] **`X2TIF` constructor swallows failures and returns a half-built object.** **[verified]**
  `X2TIF.java:170-178` catches `Dependency/Service/Format/IOException` and logs them *without the exception*.
  It then continues to `FindMeta`, and `Execute()` crashes later with an unrelated error (`/ by zero` from `width / tileSizeX` at `X2TIF.java:313`).
  The `HatchWriter` `IOException` is swallowed the same way (`X2TIF.java:106-110`), which leaves `writer == null` and leads to an NPE.
  A constructor that throws is never `close()`d by try-with-resources, so the reader's file handle leaks.
  *Fix:* let checked exceptions propagate, using a static factory such as `X2TIF.open(...)`. Close the reader/writer on failure. Always log the cause object.

## P1: Wrong output, hard failures on valid input, unreported failures

- [x] **Pixel-spacing metadata uses the wrong units and differs between formats.**
  *Done:* every reader now reports mm/pixel from the physical pixel size (SVS falls back to `MPP`). Covered by `InputHandlingTest.pixelSpacingIsRecordedInMillimetres`.
  The XMP field is named `SizePerPixel*InMM` and emitted as DICOM `PixelSpacing`, which is in mm.
  The VSI path (`X2TIF.java:229-238`) actually produces **nm/pixel**: 0.25 µm → `250`.
  The SVS path (`X2TIF.java:252-254`) produces **MPP × 10⁶**: 0.25 µm → `250000`.
  The VSI value is off by 10⁶ and the SVS value by 10⁹, and the two disagree by 1000×.
  *Fix:* compute mm/pixel once from `ppx/ppy` for every reader (`µm / 1000`) and add a unit test.

- [x] **VSI without physical pixel size fails to convert.** `X2TIF.java:229-234`.
  With `ppx == null`, `SetPPS` yields `px = 0/1000`, and then `BigDecimal.ONE.divide(0)` throws `ArithmeticException` in the constructor.
  *Fix:* skip pixel-spacing metadata when calibration is unknown.

- [x] **Uncalibrated inputs get `XResolution`/`YResolution = 0/1000`.** **[verified]**
  `X2TIF.java:296-297, 322-324`. A zero resolution is invalid and some readers divide by it.
  *Fix:* omit the resolution tags (and `ResolutionUnit`) when the size is unknown.

- [x] **SVS metadata parsing fails the whole conversion when keys are missing.** `X2TIF.java:242, 252`.
  `Double.parseDouble((String) list.get("AppMag"))` and `...get("MPP")` throw an NPE when the key is absent.
  This is outside any try, so a valid SVS without `AppMag`/`MPP` cannot be converted. That includes labels and macros selected via `-s`, SVS files not written by Aperio, and `MetadataLevel.MINIMUM`, where `SVSReader.java:384` stores nothing.
  Because the constructor throws, both the reader stream and the already-open `HatchWriter` leak, one handle per failed file in batch mode.
  *Fix:* treat all XMP metadata as best-effort (null checks plus `NumberFormatException` handling).

- [x] **SVS photometric/subsampling branch writes an invalid IFD.** `X2TIF.java:370-376`.
  *Done:* replaced by tags derived from the tiles themselves (next item).
  When `YCbCrSubSampling` is present and not `[2,2]`, `PhotometricInterpretation` is never written for level 0, even though the tag is required.
  `samp` is a `short[]`, which Bioformats `TiffSaver` serializes as type **BYTE**, not SHORT. This mechanism is confirmed: the XMP tag, written from a `short[]`, comes out as BYTE.
  *Fix:* always set photometric, and pass subsampling as `int[]`.

- [x] **Level-0 photometric/subsampling is hard-coded instead of read from the data.** `X2TIF.java:363-384`.
  *Done:* Photometric and YCbCrSubSampling now come from the first stored tile's SOF/APP14/JFIF markers, and every tile must match. **[verified on real data]** 5 of the 6 VSIs in `/d/hatchtest` store 4:2:2 tiles; their old outputs (tagged `[1,1]`) fail to decode through libtiff's JPEG codec.
  VSI and TIFF inputs are always tagged `YCbCr` with `[1,1]`.
  A 4:2:0 JPEG tagged `[1,1]` draws libtiff warnings and breaks strict readers. The test fixture already does this: ImageIO emits 4:2:0.
  An RGB JPEG (Adobe APP14 transform=0) tagged YCbCr decodes with wrong colors.
  The tags can contradict the tile bytes themselves: for RGB-photometric sources, `getRawTile` *injects* APP14 transform=0 (`TiffParser.java:1445-1451`), but the TIFF path still tags the output YCbCr.
  libtiff in `JPEGCOLORMODE_RGB` trusts the Photometric tag over APP14, so OpenSlide and vips show a colour cast while ImageIO and browsers look fine. Self-contained RGB tiles (no JPEGTables) get no APP14 at all.
  SVS reads its tags from `getIFDs().get(0)` (`X2TIF.java:368-371`) even when `-s` selects a different IFD.
  *Fix:* expose the IFD actually being read (e.g. `getCurrentIFD()`) and copy Photometric/YCbCrSubSampling from it. Failing that, parse the first tile's SOF/APP14 markers.

- [x] **Non-JPEG or non-RGB8 inputs are never rejected.** `X2TIF.java:128-131`.
  *Done:* each reader's `getRawTileLayout()` checks the IFD/ETS actually read, and X2TIF checks for UINT8, 3 channels and one plane before writing anything.
  The code says "trying JPEG...no promises", and `.vsi`/`.tif` compression is never checked; `xcompression` stays null for VSI, so the gate never fires.
  - For VSI, a JPEG-2000/RAW/lossless ETS reaches `throw new Error("NOT JPEG!!")` (`CellSensReader.java:1043`) at the first tile, *after* the output file exists. That `Error` gets past every `catch (Exception)`.
  - Multichannel fluorescence or Z/EFI-stack VSIs convert only plane 0 (`X2TIF.java:408` always passes `no=0`).
  - A 1-channel JPEG ETS writes grayscale JPEGs under `SamplesPerPixel=3`.
  - For `.tif`, LZW/Deflate/J2K/raw tiles are copied into a `Compression=7` container.
  - SVS `"Compression"` describes IFD 0 only (`BaseTiffReader.java:113,132`), so `-s` pointing at an LZW label/macro image passes the check.
  - The check matches by *name*, so ALT_JPEG (33007, also named "JPEG") passes too.
  - Striped and PlanarConfig=2 inputs are treated as tiles. Strips become tiles whose width is not a multiple of 16, and PlanarConfig=2 returns only the first component.

  `TiffParser.getRawTile` validates nothing either: its compression check is commented out (`TiffParser.java:1393`). Row/col are not range-checked; on an `OnDemandLongArray`, an out-of-range index reads unrelated bytes as a tile offset.
  *Fix:* check the IFD/series actually being read, not a global key. In `getRawTile`, range-check row/col. Before writing anything, check that compression code is 7, `UINT8`, `RGBChannelCount == 3`, `imageCount == 1` and that the input is tiled; fail with a clear `FormatException` otherwise. Replace the `Error` with `FormatException`.

- [x] **Missing or sparse tiles become invalid tile data, in every reader.**
  *Done:* readers return `null`; X2TIF writes one cached placeholder in the series' background colour, encoded like the real tiles (`JPEGTools.blankTile`).
  For TIFF/SVS, `TiffParser.getRawTile` returns a **zero-filled raw buffer** when `TileByteCount == 0` or the offset is past end-of-file (`TiffParser.java:1431-1436`). That buffer is written as a JPEG, so level 0 already contains an undecodable tile. `ImageIO.read` then returns null, and the run ends in one of three ways: `Error("NW TILE NULL!!!")`, an NPE in `Shrink` (`Pyramid.java:158`), or a silently blank quadrant. This affects GDAL `SPARSE_OK` files and slides with omitted tiles (OpenSlide special-cases these for Aperio). The same branch also hands back the caller's `buf` (aliasing); today every caller passes `null`.
  For VSI, `CellSensReader.java:996-1022` has the following problems:
  - The stored background colour is computed and then painted over with `Color.BLACK`.
  - The placeholder is re-encoded with ImageIO defaults (4:2:0) under an IFD tagged `[1,1]`.
  - It is grayscale for 1-channel images, and a new JPEG is encoded for every missing tile.
  - The `ImageIO.write` return value is ignored, so a `false` result yields a 0-byte tile.

  *Effect:* sparse (tissue-detected) brightfield VSIs show black rectangles on a white background at every level, and strict readers such as libtiff and OpenSlide reject the sampling mismatch.
  *Fix:* use one shared placeholder generator for all readers: one JPEG per series, cached and filled with the background colour (white if unset). Encode it with the same sampling as the real tiles, and check the write result.

- [x] **For `.tif`/`.svs`, the tile grid comes from IFD 0 but tiles are read from the selected IFD.**
  *Done:* the grid comes from `getRawTileLayout()`, which shares `getRawIFD()` with the tile reads.
  `getOptimalTileWidth/Height` always read `ifds.get(0)` (`MinimalTiffReader.java:450-487`), while `getRawBytes` reads `ifds.get(getSeries())` (`MinimalTiffReader.java:300-304`) and indexes with *that* IFD's `TilesPerRow`.
  On top of that, the height falls back to the 1 MB/row-bytes heuristic for tiles over 10 Mpx, or for height ≤ 1.
  - *Scenario:* a `.tif` whose IFD 0 is a striped preview and whose IFD 1 is the tiled full-res image. Tiles land in the wrong place, then an `ArrayIndexOutOfBoundsException` is thrown.
  - *Scenario:* an image wider than about 349k px gets a tile height of 0, which divides by zero at `X2TIF.java:314`.

  *Fix:* in X2TIF, take `TileWidth`/`TileLength`/`TilesPerRow`/`TilesPerColumn` from the same IFD that `getRawBytes` reads, without the openBytes heuristics.

- [x] **Regression in the IFD-chain offset rule silently drops images.** `TiffParser.java:1349`.
  *Done:* matches upstream now. There is no unit test: it needs a 2–4 GiB classic TIFF.
  The fork tests `in.length() > Integer.MAX_VALUE`, where upstream 8.3.0 tests `>= 2^32`. In a classic TIFF of 2–4 GiB whose IFD chain points backwards (common after `tiffset`, or after de-identification rewrites IFD0 at the end of the file), 4 GiB is added to the next offset. The offset then lands past EOF and the IFD walk stops, so pyramid levels, labels, or the base image itself are silently missing.
  *Fix:* use `in.length() >= 0x100000000L`.

- [x] **Replace all `throw new Error(...)` with exceptions.** `Error` gets past `catch (Exception)` in `Hatch` and is lost in batch futures, and most of these sites drop the cause. The sites:
  - `CellSensReader.java:1043`
  - `TiffParser.java:1453` (grayscale/other-photometric JPEG with JPEGTables). That method also reads photometric even when there are no tables, so a file without a Photometric tag hits an NPE (`IFD.java:716-717`).
  - `X2TIF.java:135, 345, 385, 416`
  - `Pyramid.java:294`
  - `JPEGTools.java:60, 62`

- [x] **A `.vsi` without its `_name_/stack*` folder crashes with an opaque error.** `CellSensReader.java:952`, `648-656`.
  *Done:* **[verified]** with a copied `.vsi`, conversion now fails with "Missing expected .ets files in …".
  A missing ETS folder only produces a warning. `MaxImage` then picks the VSI's own IFD, and `tileMap.get(...)` throws `IndexOutOfBoundsException: Index 0 out of bounds for length 0`.
  The same happens when `IMAGE_BOUNDARY` is missing: the null guard at `CellSensReader.java:1374-1379` leaves `sizeX = 0`.
  *Fix:* fail in `setId` with "ETS data folder not found: …". Throw a `FormatException` when the selected series isn't ETS-backed, and fall back to `cols*tileX` for the size.

- [x] **Orphan/extra ETS files are mapped to pyramids by position.** `CellSensReader.java:700-705, 1278, 1374`.
  *Done:* ported upstream's size matching and `frame_*.ets` filter, plus an explicit core→pyramid map that is also used for metadata. No orphan sample exists in the corpus, so this path has only been reviewed, not run.
  ETS file *s* is blindly paired with `pyramids.get(s)`. A stale extra stack directory either throws an IOOBE in `setId` or, if it sorts first, gives the main image the wrong pyramid's dimensions, tile grid and physical size.
  *Fix:* port upstream's `hasOrphanEtsFiles` matching and its `frame_*.ets` filter.

- [x] **VSI `TILE_ORIGIN` is ignored.** `CellSensReader.java:179, 2124`; upstream applies it.
  *Done:* parsed as upstream does. A non-zero origin widens the output to the stored grid (offset logged), so no image pixels are dropped. None of the corpus VSIs has a non-zero origin, so this path is untested on real data.
  With a non-zero origin, the output is misregistered by the origin offset, and `ceil(sizeX/tileW)` can drop a real tile column/row at the right/bottom edge.
  *Fix:* parse `TILE_ORIGIN`. Either size the output from the full grid (`cols*tileX`) and record the offset, or re-encode the edge tiles.

- [x] **Hidden `-jp2` flag leads straight to a crash.** `HatchParameters.java:49`, `X2TIF.java:132-133, 345`.
  JPEG-2000 passes the compression check, then hits `throw new Error("Should never get here")`.
  An `Error` gets past `catch (Exception)`: single-file mode prints a stack trace, and batch mode loses it silently (see the next item).
  *Fix:* remove the flag until JPEG-2000 is actually supported.

- [x] **Batch failures can vanish, and the exit code is always 0.** **[verified, exit code]**
  `Hatch.java:68` drops the `Future` from `engine.submit(...)`.
  Anything thrown that isn't caught inside `call()` is discarded: `OutOfMemoryError`, `Error`s, and `Validate.file`'s `IllegalArgumentException` at `Hatch.java:213, 218, 233`, which sits outside the try.
  `main` returns 0 even for a missing `-src` or failed conversions, so scripts and schedulers cannot detect failure.
  *Fix:* catch `Throwable` per job, record success/failure, print a summary, and `System.exit(failures > 0 ? 1 : 0)`.

- [x] **The JVM can hang after a directory-walk error.** `Hatch.java:44-73`.
  `Files.walk` throws `UncheckedIOException` (for example on a permission-denied subfolder), and only `IOException` is caught.
  That exception skips `engine.shutdown()`, and the prestarted non-daemon pool threads keep the JVM alive forever.
  *Fix:* call `shutdown()` in `finally` and catch `UncheckedIOException`.

- [x] **CLI arguments are not validated up front.** **[verified]**
  `-fp 0` passes `PositiveInteger` (`PositiveInteger.java:11` checks `< 0`) and then crashes with `maximumPoolSize must be positive`.
  `-q` outside `(0,1]` is only caught by ImageIO after level 0 has been written (`Quality out of bounds!`).
  A non-numeric `-s` throws an uncaught `NumberFormatException` at `Hatch.java:137`.
  *Fix:* validate all three in `HatchParameters` (JCommander validators).

- [x] **`new BufferedImage(w, h, bi.getType())` breaks on `TYPE_CUSTOM` images.**
  `Pyramid.java:149, 234, 257`. ImageIO returns `TYPE_CUSTOM` (0) for JPEGs with an embedded non-sRGB ICC profile, and `new BufferedImage(...,0)` then throws `IllegalArgumentException`.
  *Fix:* allocate `TYPE_3BYTE_BGR` explicitly (input is already restricted to RGB8).

## P2: Performance, scalability, robustness

- [x] **Writing tiles is O(N²) because the whole IFD is rewritten after every tile.** **[verified]**
  *Done:* `TiledTiffWriter` appends tiles and keeps offsets and byte counts in memory. Each IFD is written once, when the file is finished, last level first so each IFD already points at its successor; then the header is pointed at the first. There is no "last tile" flag any more (the cause of the P0 depth == 1 bug). `HatchWriter`/`HatchSaver` are deleted.
  `HatchSaver.java:38-72`. Every tile clones both `long[numTiles]` arrays and rewrites the full IFD, including both arrays.
  Measured writer-only cost: 1,024 tiles take 0.5 s, 4,096 take 1.9 s, and 16,384 take **12.4 s**.
  Extrapolated, a ~120k-tile slide (100k×80k px at 256² tiles) spends about **13 minutes** on IFD rewrites alone.
  *Fix:* append tiles while filling the offset/count arrays in memory, then write the IFD once per level. Either reserve its slot up front and rewrite it once, or write it after the tiles and patch the previous level's next-IFD pointer.

- [x] **Each pyramid level is JPEG-encoded three times, compounding generation loss.**
  *Done:* `PyramidBuilder` computes each level from the *pixels* of the level above (2×2 box filter) before they are encoded, so every reduced tile is encoded exactly once and never re-decoded. **[verified]** On CMU-1 the reduced levels are closer to an exact reduction of the source than before at the same quality (mean |diff| L1 0.54 vs 0.58, L3 0.78 vs 0.98, L5 3.62 vs 4.49).
  Per level:
  1. `Lump` encodes a 2T×2T merged JPEG (`Pyramid.java:292`).
  2. `Shrink` decodes it, downsizes it and encodes again (`Pyramid.java:225-236`).
  3. `GetImageBytes` decodes the stored tile and encodes it *again* for output (`Pyramid.java:109-131`), even though `tiles[a][b].GetBytes()` already holds a JPEG at quality `q`.

  Level *k*'s output has therefore been through 2*k*+1 lossy encodes where *k* would do, and CPU use is roughly 3× what it needs to be.
  *Fix:* merge and downsample in one step: decode 4 tiles, draw them scaled into a T×T image, encode once. Write and keep those same bytes.

- [x] **All of level 0 is held in heap.** `X2TIF.java:415` → `Pyramid.put`.
  *Done:* base tiles stream through. Each reduced level holds one tile row of pixels, about 3 × tile height × base width bytes over all levels (~80 MB for a 100k-px-wide slide with 256-px tiles), independent of the slide's height.
  Every compressed level-0 tile stays in memory until level 1 is built. That is multiple GB for large slides, multiplied by `-fp`.
  *Fix:* build level 1 incrementally from pairs of tile rows as they stream in, or read level 0 back from the output file. At minimum, document `-Xmx` sizing.

- [x] **Pyramid work runs on unbounded virtual threads.** `Pyramid.java:156, 179`.
  *Done:* a fixed pool of `availableProcessors() / fp` daemon platform threads; at most two base tile rows of work are queued at a time.
  One virtual thread per tile for CPU-bound, JNI-pinned JPEG work has no backpressure, and `-fp N` multiplies it.
  *Fix:* use a bounded platform pool sized to `availableProcessors()/fp`.

- [x] **Default `-q 1.0` is JPEG quality 100.** `HatchParameters.java:52`.
  *Done:* the default is 0.9, and the help text says that only the reduced levels are re-encoded. **[verified]** PC380089's output shrinks from 471 MB to 266 MB.
  This makes the pyramid levels several times larger than needed. Consider defaulting to 0.85–0.90 and documenting the trade-off.

- [x] **Edge tiles are padded with black and the padding bleeds in.** `Pyramid.java:259-260`.
  *Done:* filter reads are clamped to the image, so padding is never sampled, and the padding of reduced tiles repeats the edge pixels. **[verified]** On CMU-1 the step between the last two pixel columns/rows of the smallest level was 124 grey levels; it is now 2.
  `Merge` fills with black, so pixels on the image's right and bottom edges get blended with black (or with the source's padding garbage) as levels are downsampled.
  *Fix:* clamp/replicate edge pixels, or crop to the real extent before scaling.

- [x] **Logging is re-initialized by several classes.** `Hatch.java:28-35`, `Hatch.java:190-197`, `X2TIF.java:57-64`.
  *Done:* `Hatch.run` reads `logging.properties` once; the static initializers are gone. The error log is opt-in: `-log FILE` appends SEVERE messages to FILE, and nothing writes `./error.log` any more.
  Each static initializer calls `LogManager.readConfiguration()`, which *resets* all levels and handlers and undoes the `-v` level set in `main`.
  The `FileHandler` always creates `./error.log` in the current working directory **[verified]**; this fails in read-only directories and collides between concurrent runs.
  *Fix:* configure logging once in `main`, and make the log file opt-in (e.g. `-log <file>`).

- [x] **`Validate` only checks image dimensions.** `Validate.java:28-65`.
  *Done:* `Validate.check` walks the IFD chain itself (offsets inside the file, no loops, terminated), checks that every tile is stored inside the file, decodes the last tile of each level, checks Photometric/YCbCrSubSampling against that tile's JPEG markers, and checks that each level halves the one before and the smallest fits in 1024×1024. **[verified]** It passes all new real-data outputs (10–200 ms each) and rejects an old output of a 4:2:2 VSI ("tags say Y_CB_CR [1, 1] but the tiles are … 2, 1 …"), so `-r` re-converts those. The TwelveMonkeys TIFF plugin it used is no longer a dependency.
  It checks the width/height of the first and last image and nothing else. It never confirms tile offsets lie within the file or that any tile decodes, so a truncated file can pass.
  *Fix:* also check each IFD's offsets+counts ≤ file length, decode one tile per level, and check the chain terminates.

- [x] **Depth uses floating-point `log` and only the tile width.** `X2TIF.java:146-149`.
  *Done:* `PyramidBuilder.levelCount` halves with integers and uses both tile dimensions. It keeps the existing rule (halve until the image fits in *half* a tile), so level counts are unchanged for square power-of-two tiles; `PyramidTest` checks this against the old formula.
  `Math.log(x)/Math.log(2)` can overshoot for exact powers of two, and `tileSizeY` is ignored.
  *Fix:* loop with integer halving until both dimensions are ≤ one tile.

- [x] **VSI tile reads open a new file stream per tile and scan it byte by byte.** `CellSensReader.java:1026`, `JPEGTools.java:23-44`.
  *Done:* one `FileChannel` per ETS file, closed with the reader. Each tile is one positional read of the chunk table's byte count, trimmed to the end of the JPEG stream, which `JPEGTools.streamLength` finds by walking the markers (an EOI inside an APPn segment no longer cuts the tile short). `FindFirstEOI` is gone. **[verified]** All 58,349 level-0 tiles of the four previously validated VSIs are byte-identical to the P1 outputs.
  Each tile opens a new `RandomAccessInputStream`, which buffers 1 MiB at offset 0 and again after the seek. The code then walks to the EOI marker with `readByte()`.
  That is about 2 MiB of copying plus an open/close per ~100 KB tile, or tens of GB of buffer fills for a 40k-tile slide.
  The chunk table's per-tile `nBytes` is read and thrown away (`CellSensReader.java:1254`).
  `FindFirstEOI` can also stop early at an `FFD9` inside an APPn segment (e.g. an EXIF thumbnail), which truncates the tile.
  *Fix:* keep one stream per ETS file (closed in `close()`), store `nBytes`, and `readFully` exactly that many bytes. Keep the EOI scan only as a fallback.

- [x] **TIFF/SVS tile reads are O(N²): every tile does work proportional to the total tile count.**
  *Done:* `JpegTiffTiles` resolves the offsets, byte counts and JPEGTables prefix once per IFD; each tile is then one positional read. **[verified]** TCGA-BH-A0BG (99,814 base tiles) converts in 11 s (31 s at `-q 1.0`), down from 565 s.
  For each tile, `getStripByteCounts()` copies the whole count array (`IFD.java:836-846`). For BigTIFF it first re-reads the entire `TileByteCounts` array from disk through `OnDemandLongArray.toArray()`. `getStripOffsets()` loops over every offset (`IFD.java:774-778`), and is called from `TiffParser.java:1407, 1426`.
  A ~140k-tile slide pushes roughly 150 GB through memory this way.
  *Fix:* resolve offsets/counts once per IFD, or use `OnDemandLongArray.get(i)` for both. Cache the JPEGTables + APP14 prefix per IFD.

- [x] **VSI tile lookup is O(N²).** `CellSensReader.java:996`.
  *Done:* `TileCoordinate.hashCode`, and one `HashMap` from coordinate to chunk per ETS file (the first chunk wins, as with the linear search).
  `ArrayList.indexOf(TileCoordinate)` scans every chunk (all resolutions and planes) for each tile: about 6×10⁸ `equals` calls for 30k tiles. `TileCoordinate` (`CellSensReader.java:2503-2539`) has no `hashCode`.
  *Fix:* add `hashCode`, and build a `HashMap<TileCoordinate,Integer>` once in `parseETSFile`.

- [x] **CellSens errors are swallowed and streams leak.**
  *Done:* `readTags` logs a warning with the cause; like upstream it carries on, since the tags are descriptive metadata. `reopenFile` closes the parser it replaces. A failing `initFile` closes the reader, so the `.vsi` is released (`TiffTilesTest`).
  - `readTags` swallows every exception (`CellSensReader.java:1980-1982`), including "Invalid dimension", so metadata is silently truncated.
  - `reopenFile` replaces `parser` without closing it (`CellSensReader.java:558`).
  - A failed `initFile` leaves the `.vsi` open; X2TIF never calls `close()` on that path, and on Windows this locks the file.

- [x] **TIFF reader errors are swallowed and streams leak.**
  *Done:* the forked readers are gone (next item). `HatchTiffReader` and `HatchSVSReader` close themselves when `initFile` fails, and the tile path does not depend on `initTiffParser`.
  - `MinimalTiffReader.java:312-314` logs a `FormatException` at SEVERE and carries on.
  - `initTiffParser` (`MinimalTiffReader.java:744-752`) logs the `IOException` and then builds `new TiffParser(null)`.
  - `FormatReader.setId` (`FormatReader.java:1415`) doesn't close the reader if `initFile` throws.

  *Fix:* rethrow, and close on init failure.

- [x] **Replace the reader forks with thin subclasses of upstream Bioformats.**
  *Done:* deleted the forked `FormatReader`, `MinimalTiffReader`, `BaseTiffReader`, `TiffReader`, `SVSReader`, `SubResolutionFormatReader`, `TiffParser`, `IFD`, `IFDList`, `TiffCompression` and `NeoJPEGCodec` (~7.5k lines). `HatchTiffReader` and `HatchSVSReader` subclass the upstream readers; `CellSensReader` stays forked but extends upstream `FormatReader`. All three implement `RawTileSource`; the TIFF/SVS tile reading lives in `JpegTiffTiles`. The fork's two `TiffParser` fixes are not needed: the tile path reads the offset arrays itself, and image IFDs always have more than one entry.
  **Behaviour change:** SVS series numbers now match upstream (and `showinf`). The striped thumbnail is no longer a series, so `-s` indices of the label and macro images drop by one (CMU-1: label 4 → 3, macro 5 → 4). The thumbnail could not be converted anyway (it is striped). About 4.5k lines of the forked `FormatReader`/`MinimalTiffReader`/`BaseTiffReader`/`TiffReader`/`SVSReader`/`SubResolutionFormatReader` exist only to add `getRawBytes`.
  - Upstream `loci.formats.in.SVSReader.openCompressedBytes(no, col, row)` already returns the JPEGTables plus the verbatim tile with bounds checks; only the APP14 insertion needs adding, via the protected `getIFD(no)`.
  - For TIFF, subclass `loci.formats.in.TiffReader` and use the protected `ifds`, `tiffParser` and `getCompressedByteCount`.
  - Watch out: upstream `copyTile` over-reads 2 bytes past the tile end, and returns an empty array for missing tiles.
  - `CellSensReader` must stay forked (upstream internals are private). Have it implement a small `RawTileSource` interface instead of forcing a fork of `FormatReader`.
  - Also drop the `TiffParser`/`IFD`/`IFDList`/`TiffCompression`/`NeoJPEGCodec` forks, about 2.8K lines of duplicated code; `IFD` is upstream byte-for-byte apart from the package name.
    - Conversion only needs header/IFD parsing plus a ~60-line `readRawJpegTile(stream, ifd, row, col)` helper with validation, cached offsets/counts and table splicing.
    - Carry forward the fork's two genuine fixes, which 8.3.0 lacks: the `(long) count * bpe` overflow cast (`TiffParser.java:459`) and keeping 1-entry IFDs (`TiffParser.java:429`).
    - `TiffParser` is not thread-safe (shared stream, mutable `codecOptions`). That is fine for today's one-reader-per-job design, but document it.

- [x] **`JPEGBuffer.GetBufferImage` turns decode failures into `null`.** `JPEGBuffer.java:31-38`.
  *Done:* `JPEGBuffer` is gone. Decoding is `JPEGTools.decode`, and a failure surfaces as an `IOException` that names the tile and keeps the cause.
  *Progress:* `Dump2ByteArray` now throws `UncheckedIOException` with the cause and disposes the writer in `finally`. `GetBufferImage` still returns null.
  The caller then fails with an NPE or `Error("NW TILE NULL!!!")` (`Pyramid.java:294`), and the original cause is lost.
  `JPEGTools.Dump2ByteArray` wraps exceptions in `new Error(msg)`, also without the cause (`JPEGTools.java:60-62`), and `dispose()` isn't in a `finally`.
  *Fix:* propagate `IOException`/`UncheckedIOException` with the cause attached.

## P3: Cleanup, build, docs, conventions

### Dependencies and build
- [ ] **Replace Apache Jena with a few lines of XML.** Jena is ~13 MB and 20+ jars (TDB1/2, SHACL, ShEx, RDF-patch) out of a 66 MB classpath, used only to write one small XMP packet (`XMP.java`). Dropping it also simplifies the native-image config.
- [ ] Remove the unused `me.tongfei:progressbar` dependency, which also pulls in jline (`pom.xml:50`).
- [ ] `XMP.java:8` relies on `commons-codec`, which only arrives transitively through Jena. Use `java.util.Base64`.
- [ ] SLF4J versions are skewed: api 2.0.9 (via Bioformats), simple 2.0.12, jcl-over-slf4j 2.0.17. Pin `slf4j-api` to match.
- [ ] Remove the `halcyon` repository (`pom.xml:100-101`). **[verified]** None of the 111 resolved jars come from it; it only adds a build dependency on an external host.
- [ ] Version string is duplicated: `Hatch.java:24` hard-codes `4.3.0`. Read `Implementation-Version` from the manifest, or a filtered resource.
- [ ] Add CI (GitHub Actions: `mvn -B verify` on JDK 21, plus a native-image smoke build).

### Native image
- [ ] `-march=native` (`pom.xml:210`) makes the distributed binary crash (SIGILL) on older CPUs. Use `-march=compatibility` or `x86-64-v3` for release builds.
- [ ] `<debug>true</debug>` and `<verbose>true</verbose>` in the release profile bloat the binary and the logs (`pom.xml:201, 203`).
- [ ] `config/reachability-metadata.json` is stale. Since P2 it also lists TwelveMonkeys classes that are no longer on the classpath, and lacks the new classes. It was traced on Linux (X11/XRender entries) for Jena 5.2/Bioformats 7.x, and it lacks `SingleLineFormatter`, which `LogManager` loads reflectively. Regenerate it with the tracing agent while running the test suite, per OS.
- [ ] `--add-reads edu.stonybrook.bmi.hatch=ALL-UNNAMED` (`pom.xml:179-180`) refers to a module that doesn't exist (there is no `module-info.java`). Remove it.

### Metadata (XMP)
- [ ] `PixelSpacing` is serialized as an RDF collection (`rdf:first`/`rdf:rest`) **[verified]**, which isn't valid XMP. Use `rdf:Seq`.
- [ ] `http://ns.adobe.com/DICOM/` isn't a real Adobe namespace. Use a namespace you own. Drop the unused `xmp`, `xmpMM` and `xsd` prefixes.
- [ ] SVS "Manufacturer" looks up `"Image Description"` (`X2TIF.java:243`), a key `SVSReader` never stores, so it is always null. The free-text vendor line (e.g. "Aperio Image Library v…") is stored under global `"Comment"`. Use that, or a fixed "Aperio".
- [ ] `builder.build()` at `XMP.java:117` does nothing (`.output(os)` already wrote the packet). Use `" ".repeat(2424)` for the padding at `XMP.java:124`.
- [ ] Levels ≥ 1 drop `X/YResolution` (`X2TIF.java:428-429`). Write per-level scaled values so viewers can calibrate any level.

### Dead code and clarity
- [ ] `X2TIF`: the OME-XML `meta` block (`X2TIF.java:154-169`) is built and never used, as are the `compression`/`method` switches and the unreachable `openCompressedBytes` branch (`X2TIF.java:388-417`), `effSize`, and ~25 lines of commented-out code.
- [x] *(moot: `Pyramid` was replaced by `PyramidBuilder`)* `Pyramid`: remove the unused `put(byte[],…,float)` and `put(BufferedImage,…,float)` overloads, the `xscale` field, and the public mutable `DownScale` (make it `static final`).
- [x] *(moot)* `Pyramid.java:157-163`: the `Shrink` corner check can never fire, because `Merge` always produces 2T×2T tiles. It decodes the corner tile twice per level, and if it ever did fire it would leave `tilesX` and `width` inconsistent. Remove it.
- [x] *(moot: `HatchSaver` was deleted)* `HatchSaver.java:55-56`: two no-op `seek` calls left over from `TiffSaver`.
- [x] `HatchParameters` has no `toString()`, so `Hatch.java:106` logs an object hash. *Done:* that log line was removed.
- [ ] `StopWatch` prints to `System.out` while everything else goes through the logger.
- [ ] Empty `catch (NullPointerException ex) {}` at `X2TIF.java:228` hides real metadata bugs. Use explicit null checks.
- [ ] Rename PascalCase methods (`Execute`, `Traverse`, `MaxImage`, `SetPPS`, `FindMeta`) to Java camelCase. (`Lump`, `Shrink`, `GetImageBytes` and `FindFirstEOI` are gone.)
- [ ] Extension handling assumes 4-character extensions (`Hatch.java:63, 91`). Batch mode skips `.tiff` even though `TiffReader` accepts it, and `-dest out.tiff` is treated as a directory (`Hatch.java:126`).
- [x] `HatchWriter` constructor leaks the `RandomAccessOutputStream` if `writeHeader()` throws (`HatchWriter.java:19-27`).

### Licensing
- [ ] Restore the upstream GPL copyright/license header stripped from `CellSensReader`, the one remaining forked file, and add a `NOTICE` file listing what was modified. (The other forks were deleted in P2.)

### Forked Bioformats readers
- [ ] **`CellSensReader` was forked from an older upstream and is missing later fixes:**
  - the `frame_*.ets` filter
  - orphan-ETS matching
  - `TILE_ORIGIN`
  - the BGR swap for RAW tiles
  - the `otherExposureTimes` fallback
  - `PhysicalSizeZ`
  - `getAvailableOptions`, so `cellsens.fail_on_missing_ets` can't be set

  Rebase it on 8.3.0, or better, subclass the upstream reader and add only `getRawBytes`.
- [x] *(moot: `CellSensReader` now extends upstream `FormatReader`)* **Regression vs upstream in the `FormatReader` fork.** `FormatReader.java:1282-1283` uses `core.get(i).resolutionCount` where upstream uses `core.get(index)`. It is wrong in non-flattened mode; latent today, but `CellSensReader` extends this class directly. Revert it.
- [x] *(moot: fork deleted)* `MinimalTiffReader.java:533` dropped upstream's typed `getIFDValue(NEW_SUBFILE_TYPE, Number.class)`, so a malformed value now throws `ClassCastException`.
- [x] *(moot: `HatchSVSReader` subclasses upstream)* **`SVSReader` is forked from a pre-8.x upstream.** The fork is missing:
  - NewSubfileType-based label/macro detection
  - the label/macro index fix-up
  - `removeThumbnail`, so the striped thumbnail is kept as an extra "resolution"
  - Left/Top

  `getMagnification()` (`SVSReader.java:598-600`) unboxes null into an NPE, where upstream returns NaN.
- [x] *(moot: fork deleted)* `TiffReader` dropped `m.orderCertain = true` (looks accidental) and has a redundant `getIFDs()` override. `openCompressedBytes` always throws because the upstream `ICompressedTileReader` implementation was removed.
- [x] *(moot: replaced by `RawTileSource.getRawTile(row, col)`)* `getRawBytes(byte[] rawbuffer, …)`: the TIFF/SVS paths ignore `rawbuffer` (`MinimalTiffReader.java:315` passes `null`), so X2TIF's `tileW*tileH*24` buffer is used only by VSI.
- [x] **JPEG-table splicing is fragile** (`TiffParser.java:1386, 1438-1456`).
  *Done* in `JpegTiffTiles`: `byte[]` or `short[]` tables, SOI/EOI checked on the tables and SOI on each tile, tables under 4 bytes treated as absent. `TiffTilesTest` splices synthetic Aperio-style tables and checks the pixels.
  - A BYTE-typed `JPEGTables` tag comes back as `short[]`, and a count of 1 comes back as `Byte`; both throw `ClassCastException`.
  - Tables shorter than 2 bytes give a negative `arraycopy` length.
  - Nothing checks the tables' FFD8…FFD9 markers or the tile's SOI.

  Accept `byte[]` or `short[]`, validate the markers, and treat tables under 4 bytes as absent. (The splice arithmetic itself is correct.)
- [x] **Delete `NeoJPEGCodec` and use `loci.formats.codec.JPEGCodec`.** *Done.*
  - In `decompress`, the SOI marker scan never matches: it compares signed `read()` results with `0xff`/`0xd8` (`NeoJPEGCodec.java:63-68`).
  - `Math.max(0, cb-128)` throws away negative chroma (`NeoJPEGCodec.java:109-110`; also an upstream bug).
  - `compress` is dead code: it hard-codes quality 0.7, forces `channels = 3`, and swallows `IOException`.
- [x] *(moot: fork deleted)* **`TiffParser` regressions vs upstream** (on paths conversion doesn't use today):
  - single BYTE values come back signed (`TiffParser.java:530`)
  - ZSTD was removed from `TiffCompression`, so a Zstd TIFF throws `EnumException`
  - `getTile` was restricted to JPEG (`TiffParser.java:706`), which breaks `getSamples` for LZW/Deflate IFDs, e.g. the VSI overview read at `CellSensReader.java:549`
  - the float ReferenceBlackWhite fallback is gone
  - the offset check sits before the fakeBigTiff fix-up (`TiffParser.java:516-526`)

  Unused: `getIFDs()` (deprecated), `getFirstIFDEntry`, `getThumbnailIFDs`, `getNonThumbnailIFDs`, and ~450 lines of `getTile`/`getSamples`/`unpackBytes` reachable only from `openBytes`.
- [ ] `CellSensReader` dead code: `openBytes`/`decodeTile`/`openThumbBytes`/`getIFDIndex`, the J2K/lossless/APNG/BMP branches, the unused `reader` + `finally`, `options` and `tileSize` in `getRaw` (`CellSensReader.java:1025-1049`), the always-false `tileMap.get(...) == null` check (`CellSensReader.java:952`), the unused `java.util.logging` imports, and discarded locals (`headerSize`, `version`, `colorspace`, `compressionQuality`, `tileZ`).
  *Progress:* the 8 unused imports and the always-false `tileMap` null check are gone. `getRaw` no longer has the unused `reader`/`options`/`tileSize`.

### Tests and docs
- [ ] Add regression tests for every P0/P1 item above: CLI path logic (same file, nested dest, collisions, `-o`/`-r`), depth == 1, missing calibration, non-JPEG input rejection, exit codes.
- [ ] Extend the synthetic fixtures. They still never produce a striped first IFD, a classic (non-Big) TIFF or an uncalibrated VSI. *Progress:* JPEGTables, sparse tiles, RGB photometric and truncated files are now covered (`TiffTilesTest`, `InputHandlingTest`).
- [ ] Add small real SVS and VSI fixtures, e.g. OpenSlide's freely licensed test slides (`CMU-1-Small-Region.svs`). Today only synthetic TIFFs exercise the readers, so the SVS and VSI paths are untested.
- [ ] Validate outputs with an independent reader in CI (libtiff `tiffinfo`, or Python `tifffile` + `imagecodecs`), not only the project's own `TiffParser`.
- [x] Fix the test fixture: it declares `YCbCrSubSampling [1,1]` for ImageIO's 4:2:0 JPEGs (`TestFixtures.java:65`).
- [ ] README: document every flag (`-fp -f -o -r -validate -validateonly -q -s -v`), the JDK 21 requirement, memory sizing, supported input (8-bit RGB, JPEG-compressed, tiled), the output layout, and the ≤1024 px smallest-level rule.
