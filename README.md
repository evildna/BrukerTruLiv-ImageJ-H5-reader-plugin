# Bruker TruLive3D (Luxendo) `.lux.h5` Reader for ImageJ / Fiji

An ImageJ 1.x plugin that opens the raw `Cam_*.lux.h5` stacks written by
Bruker/Luxendo **TruLive3D** light-sheet microscopes as properly calibrated
hyperstacks, with the complete acquisition metadata attached to the image.

Bruker's TruLive3D Embedded software saves each raw stack as an HDF5 file
(`.lux.h5`) plus Big Data Viewer and Imaris exports. Fiji can open the BDV
export, but the raw stacks themselves open with no calibration and no
metadata. This plugin fixes that: one menu entry gives you a micron-calibrated
16-bit hyperstack with exposure, filters, lasers, stage state, and the full
acquisition JSON preserved in the image header.

## Features

- **Correct spatial calibration** — voxel size read from the
  `element_size_um` HDF5 attribute (fallback: `voxel_size_um` in the embedded
  JSON), applied in microns. Scale bars and measurements are correct
  immediately.
- **Full metadata preserved** — the complete acquisition JSON (embedded
  `metadata` dataset, or the `.json` sidecar if absent) is stored in the
  image *Info* property (*Image → Show Info…*; `getMetadata("Info")` in
  macros), headed by a human-readable summary: microscope serial number and
  software version, acquisition timestamp, stack / channel / time point /
  objective / camera, exposure time, filter and dichroic selections, active
  lasers with intensities, and image ID.
- **Three open modes**
  1. *Single file* — one XYZ stack
  2. *All time points (this camera)* — scans the folder for
     `Cam_<camera>_<NNNNN>.lux.h5` and builds an XYZT hyperstack
  3. *All time points, all cameras as channels* — merges `Cam_short` /
     `Cam_long` into an XYCZT composite; channel LUT colors are derived from
     each camera's emission bandpass filter midpoint
     (&lt;480 nm → blue, 480–560 → green, 560–620 → orange, &gt;620 → red)
- **Time series support** — frame interval read from the trigger
  `interval_s` where present.
- **Memory-aware reading** — data are read plane by plane, so peak overhead
  above the final stack is a single Z-plane.
- **Macro & script friendly** — recordable dialog, plus a direct API for
  scripts.

## Installation

**Fiji (recommended)**

1. Copy `Bruker_TruLive_Reader.jar` into `Fiji.app/plugins/`
2. Restart Fiji

Fiji already ships the required HDF5 library (jhdf5, used by BigDataViewer).
If you nevertheless see `NoClassDefFoundError: ch/systemsx/cisd/...`, copy
`jhdf5-19.04.1.jar` and `cisd-base-18.09.0.jar` into `Fiji.app/jars/`.

**Plain ImageJ 1.x**

Copy all three jars (`Bruker_TruLive_Reader.jar`, `jhdf5-19.04.1.jar`,
`cisd-base-18.09.0.jar`) into `ImageJ/plugins/` and restart.

The reader then appears under **File → Import → Bruker TruLive (.lux.h5)…**
(also under *Plugins → Bruker TruLive*).

> **Memory:** files load fully into RAM. One 2048 × 2048 × 161 stack is
> ≈ 1.3 GB; both cameras of a time point ≈ 2.7 GB. Give ImageJ enough heap
> (*Edit → Options → Memory & Threads…*).

## Usage

Interactive: *File → Import → Bruker TruLive (.lux.h5)…*, pick any
`Cam_*.lux.h5` file, choose the open mode.

Macro:

```ijm
run("Bruker TruLive (.lux.h5)...");
info = getMetadata("Info");   // acquisition summary + full JSON
```

Script (Groovy / BeanShell / Jython) — direct API, no dialogs:

```java
imp = Lux_H5_Reader.openLux("/path/to/Cam_long_00000.lux.h5",
                            Lux_H5_Reader.MODE_ALL);   // or MODE_SINGLE / MODE_TIME
imp.show();
```

## The TruLive3D raw format

A TruLive session folder (e.g. `2026-09-04_134822/`) contains:

```
2026-09-04_134822/
├── raw/
│   └── stack_0_channel_0_obj_bottom/
│       ├── Cam_long_00000.lux.h5    ← raw stacks read by this plugin
│       ├── Cam_long_00000.json      ← metadata sidecar (same JSON as embedded)
│       ├── Cam_short_00000.lux.h5
│       └── Cam_short_00000.json
├── main_raw.lux.h5                  ← master file (external links to raw stacks)
├── bdv.xml / bdv.h5                 ← BigDataViewer export
└── imaris.ims / ims/                ← Imaris export
```

Each `.lux.h5` file holds:

| HDF5 object | Content |
|---|---|
| `/Data` | image data, shape (Z, Y, X), uint16 |
| `/Data@element_size_um` | voxel size `[z, y, x]` in µm |
| `/metadata` | full acquisition metadata as a JSON string |

The metadata JSON includes the affine transform to sample coordinates
(`affine_to_sample`) and the complete optics/stage state; it is preserved
verbatim in the image *Info* property.

File naming: `Cam_<camera>_<timepoint>.lux.h5`, e.g. `Cam_long_00013.lux.h5`
is camera *long*, time point 13.

## Building from source

Requires a JDK (8+) and two jars on the classpath: `ij.jar` (ImageJ) and the
CISD HDF5 libraries (`jhdf5` + `base`, both bundled with Fiji or available
from the [SciJava Maven repository](https://maven.scijava.org/)).

```sh
javac --release 8 -cp "ij.jar:jhdf5-19.04.1.jar:cisd-base-18.09.0.jar" Lux_H5_Reader.java
jar cf Bruker_TruLive_Reader.jar Lux_H5_Reader.class plugins.config Lux_H5_Reader.java
```

`plugins.config` (included in the jar) registers the menu entries:

```
File>Import, "Bruker TruLive (.lux.h5)...", Lux_H5_Reader
Plugins>Bruker TruLive, "Open TruLive .lux.h5...", Lux_H5_Reader
```

Tested with ImageJ 1.54p, jhdf5 19.04.1 (the version bundled with current
Fiji), on data written by TruLive3D Embedded v3.17.3. Compiled to Java 8
bytecode, so it runs on both Java-8 and current-Java ImageJ/Fiji
installations.

## Repository contents

| File | Purpose |
|---|---|
| `Bruker_TruLive_Reader.jar` | the plugin — this is what you install |
| `Lux_H5_Reader.java` | plugin source code |
| `jhdf5-19.04.1.jar` | HDF5-for-Java library (only needed for plain ImageJ) |
| `cisd-base-18.09.0.jar` | jhdf5 dependency (only needed for plain ImageJ) |

## Acknowledgements

- HDF5 reading via the [CISD jhdf5 library](https://sissource.ethz.ch/sispub/jhdf5)
  (ETH Zürich), the same library Fiji's BigDataViewer uses.
- Not affiliated with Bruker or Luxendo. "TruLive3D" is a trademark of its
  respective owner; this is an independent, unofficial reader for the raw
  files the instrument produces.

## License

MIT — see [`LICENSE`](LICENSE). © 2026 Xian Hu, University of Oslo.

