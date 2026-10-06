# Hatch

Hatch converts the largest image in a VSI, SVS or TIFF whole-slide image into a pyramidal
BigTIFF with a freshly built image pyramid, each level half the size of the one before.

Features:
1. The full-resolution JPEG tiles are copied from the source as they are, so the base image
   loses nothing to re-compression. (Bio-Formats tools such as bfconvert decode and re-encode.)
2. Only the largest image is converted; labels, macro and overview images are left out
   (pick another one with `-s`).
3. The reduced levels are rebuilt from the full-resolution image, each one encoded once.

## Requirements

- JDK 21 or newer to build and run the jar; Maven 3.9.
- For the native binary: GraalVM 25 with `native-image`, on Linux (the native profile uses
  the G1 collector, which GraalVM supports only on Linux).

## Build

Jar (`target/hatch-<version>.jar`, with all dependencies):
```
mvn clean package
```
Native image (`target/hatch`):
```
mvn -Pnative clean package
```

## Usage

```
hatch -src <file or folder> -dest <file or folder> [options]
java -jar hatch-<version>.jar -src <file or folder> -dest <file or folder> [options]
```

**Single file.** `-src slide.svs -dest out.tif` writes `out.tif`. If `-dest` is a folder (or
does not end in `.tif`/`.tiff`), the output is `<folder>/slide.tif`.

**Folder (batch).** `-src in -dest out` converts every `.vsi`, `.svs`, `.tif` and `.tiff` under
`in` to the same relative path under `out`, with a `.tif` extension. `out` must not be `in` or
inside it. Sources that would write the same output (say `a.svs` and `a.vsi`) are skipped and
reported. Existing outputs are skipped unless `-o` or `-r` is given.

| Option | Meaning |
|---|---|
| `-src` | Source file or folder (required) |
| `-dest` | Destination file or folder (required) |
| `-o`, `-overwrite` | Replace existing outputs |
| `-r`, `-retry` | Batch: re-convert existing outputs that fail validation, keep valid ones |
| `-validate` | Batch: validate every output, new or existing |
| `-validateonly` | Batch: validate existing outputs, convert nothing |
| `-fp N` | Batch: convert N files at once (default 1) |
| `-f`, `-filter TEXT` | Batch: only files whose path contains TEXT |
| `-s`, `-series N[,M...]` | Single file: convert these series instead of the largest image. With a folder destination, each goes to `<name>-series-<N>.tif`. Series are numbered as in Bio-Formats (`showinf`). |
| `-q`, `-quality Q` | JPEG quality of the reduced levels, 0 < Q ≤ 1 (default 0.9). The full-resolution level is copied, not re-encoded. |
| `-log FILE` | Append error messages to FILE |
| `-v`, `-verbose` | Log progress |
| `-h`, `-help` | Show the options |

The exit status is 0 when every file was converted (and, if asked, validated), and 1 otherwise.
An output is written to `<output>.part` and renamed into place only when it is complete, so a
failed or interrupted run never leaves a partial file and never destroys an existing output.

## Supported input

The image being converted must be stored as tiles of 8-bit RGB JPEG (baseline), one plane
(no Z, channels or time points). That covers brightfield Aperio SVS, Olympus/Evident VSI with
JPEG ETS files, and tiled JPEG TIFFs. Other inputs (JPEG 2000, LZW, uncompressed, striped,
grayscale, 16-bit, fluorescence stacks) are refused with a message saying why; nothing is
written for them.

A `.vsi` file needs its `_<name>_` folder of `.ets` files next to it.

## Output

- A little-endian BigTIFF. Level 0 holds the source's JPEG tiles byte for byte; its
  Photometric and YCbCrSubSampling tags are read from those JPEG streams. Tiles the source
  does not store are filled with its background colour.
- Each further level is half the previous one (rounded up), until the image fits in half a
  tile. Reduced levels are a 2×2 average of the level above, stored as JPEG YCbCr 4:2:0 at
  quality `-q`, with the same tile size as level 0.
- With a calibrated source, every level carries `XResolution`/`YResolution` (pixels per cm),
  and level 0 an XMP packet (TIFF tag 700) with the pixel spacing in mm, objective
  magnification, scanner and exposure time where the source records them.

Validation (`-validate`, `-r`) checks the IFD chain, that every tile is stored inside the file,
that one tile per level decodes and matches its level's tags, that each level halves the one
before, and that the smallest level fits in 1024×1024.

## Memory

Level 0 is streamed from the source to the output. Building the reduced levels needs about
3 × tile height × image width bytes per file: around 80 MB for a 100,000-pixel-wide slide with
256-pixel tiles, 150 MB with 512-pixel tiles. Bio-Formats needs some more for the slide's
metadata. A heap of 1–2 GB per file converted at once (`-fp`) is ample; for example
`java -Xmx4g -jar hatch.jar ... -fp 2`.

## Credits

`CellSensReader` is a modified copy of the Bio-Formats reader of the same name (GPL); see
`NOTICE`. The SVS and TIFF readers are thin subclasses of Bio-Formats'
(https://github.com/ome/bioformats).
