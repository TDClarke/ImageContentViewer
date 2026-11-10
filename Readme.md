# Image Content Viewer

An [Autopsy](https://sleuthkit.org/autopsy/) plugin that adds image forensic analysis views to the Content Viewer, so examiners can inspect an image for signs of manipulation or hidden detail without leaving the case.

![Image Content Viewer screenshot](images/imagecontentviewer.png)

## Features

| View | What it shows |
| --- | --- |
| **Colour space analysis** | Separates the image into RGB and YCbCr channels |
| **Level sweep** | Steps through brightness levels to reveal detail hidden in very dark or very light regions |
| **Luminance gradient** | Highlights edges and lighting inconsistencies that can point to compositing |
| **PCA** | Principal component analysis, which brings out variation not visible in the original |
| **Thumbnail** | Shows the embedded or generated thumbnail for comparison with the full image |
| **Noise** | Visualises sensor and compression noise patterns; inconsistencies can indicate edits |
| **Error Level Analysis (ELA)** | Highlights regions with different JPEG compression levels, which can suggest areas were altered |
| **Clone detection** | Finds duplicated regions, as left by copy-and-paste (clone stamp) edits |

## Requirements

- [Autopsy](https://sleuthkit.org/autopsy/) [version, e.g. 4.x]
- Java [version matching your Autopsy install]

## Installation

### Using the prebuilt module

1. Download `uk-ac-open-ImageContentViewer.nbm` from this repository.
2. In Autopsy, go to **Tools → Plugins → Downloaded**.
3. Click **Add Plugins…** and select the `.nbm` file.
4. Click **Install** and follow the prompts.
5. Restart Autopsy if asked.

### Building from source

The project is a NetBeans module.

1. Clone the repository:
   ```
   git clone https://github.com/TDClarke/ImageContentViewer.git
   ```
2. Open the project in NetBeans, with the Autopsy platform configured as a NetBeans platform.
3. Right-click the project and choose **Create NBM**.
4. Install the resulting `.nbm` as described above.

## Usage

1. Open a case in Autopsy and add a data source.
2. Select an image file in the Data Source tree or Results view.
3. In the lower Content Viewer panel, open the **Image Content Viewer** tab.
4. Choose an analysis from the list of views.

## Interpreting results

These views are aids for examination, not proof of tampering. Image processing, recompression and normal camera behaviour can produce similar artefacts, so findings should be corroborated and interpreted by a qualified examiner.

## Project structure

```
ImageContentViewer/
├── src/uk/ac/open/ImageContentViewer/   # Plugin source
├── nbproject/                           # NetBeans project configuration
├── build.xml                            # Ant build file
├── manifest.mf                          # Module manifest
└── uk-ac-open-ImageContentViewer.nbm    # Prebuilt Autopsy module
```

## License

[Add licence here]
