<div align="center">

# Local Dream Canvas

**[Local Dream](https://github.com/xororz/local-dream), with a real drawing canvas.**

<img src="./assets/canvas-editor.png" width="300" alt="The canvas editor with tools, color panel and the magic button">&nbsp;&nbsp;
<img src="./assets/canvas-demo.jpg" width="300" alt="A doodled flower turned into a real one by the magic button">

<sub>Left: the canvas. Right: doodle a flower, press the magic button, keep the result.</sub>

</div>

This is an unofficial fork of [xororz/local-dream](https://github.com/xororz/local-dream).
Everything else is the same app: same models, same backend, same settings. The
only goal of this fork is a better canvas for drawing and painting together with
the AI.

Upstream Local Dream only lets you scribble on top of an image you picked for
img2img. Here the canvas is a full drawing screen, with its tools laid out like
MediBang Paint.

## What's different

**Canvas mode**
- Opens from the new **Canvas** button next to img2img. With no image picked it
  starts blank at your output resolution and aspect ratio; with an image it
  becomes the base layer.
- Layers: add, duplicate, merge down, reorder, hide, opacity, rename, import an
  image as a layer.
- Brushes: pen, airbrush, eraser, blur and smudge, with adjustable size,
  opacity, hardness, spacing and strength. Bucket fill and eyedropper.
- S Pen: pressure (size and/or opacity), the eraser end erases, hover shows the
  brush outline, adjustable stabilizer. Optional "only the pen draws" mode.
- Inpaint mask brush: paint (or erase) the area the AI should redraw, shown in red.
- Selections: rectangle, lasso and magic wand (new / add / subtract), with cut,
  copy, paste as a layer, clear, and move/scale/rotate.
- Gestures: pinch to zoom and pan, two-finger tap undoes, three-finger tap redoes.

**The magic button**
- The blue button in the bottom bar generates from your drawing:
  - drawing, no selection: img2img
  - drawing with an inpaint mask: inpaint, only the red area is regenerated
    (a selection is just a selection; turn it into a mask with
    Selection → Use as inpaint mask)
  - empty canvas: txt2img

  The button shows which mode it will run (TXT2IMG / IMG2IMG / INPAINT).
- Batch count works too: you get several results and tap the one to keep.
- Your drawing and the result are shown together. ✓ keeps the result (as a new
  layer or flattened, your choice), ✗ throws it away. The prompt and settings
  stay, so you can keep drawing on top and run it again.
- The gear button holds the prompt, negative prompt, denoise strength and batch
  count, plus the usual steps / CFG / seed / sampler.

**Also**
- Saved PNGs carry the prompt in the A1111 `parameters` format, readable by
  Civitai, ComfyUI and most viewers (on by default; turn off in Settings for
  plain images like upstream). The canvas can load a prompt back from a PNG,
  which helps when you switch models.
- Fix: images finished while the app is minimized or the screen is locked are
  saved (upstream drops them). Leave a batch running in your pocket
  (careful, your phone may get very hot, unless you wanted a pocket heater).
- Canvas projects (`.ldcanvas`: layers plus prompt and settings) are saved
  separately from the generation history: Save, Save as, Save and quit, Open.
- Backup & Restore also carries canvas projects and canvas settings. Turn on
  "Compatible with original Local Dream" to export the history only, in the
  upstream format.
- Installs next to the original app (package `io.github.denius.localdreamcanvas`).
  The local backend port is a setting (default 8081, like upstream): if the
  original Local Dream is running too, set **Settings → Local backend port** to
  something else, e.g. 8091.
- No NSFW filter build: only the `basic` flavor exists.

## Install

Grab the APK from the [Releases](https://github.com/denius72/local-dream-canvas/releases) page. Requirements are the same as upstream Local Dream
(see below). If you already use Local Dream with models in `Download/LocalDream`,
set **Model storage → Downloads** in this app and grant "All files access"; it
will use the same models without downloading them again.

## Building

The native backend is not built by Gradle. As in upstream, `app/src/main/jniLibs`
and the QNN libraries are not in git. The quickest way to get them is from the
official upstream APK of the matching version (the one *without* `_with_filter`):

- `lib/arm64-v8a/libstable_diffusion_core.so` and `libdit_engine.so` go to
  `app/src/main/jniLibs/arm64-v8a/`
- `assets/qnnlibs/`, `assets/ditlibs/` and `assets/licenses/qnn-*` go to the same
  paths under `app/src/main/`

Then:

```sh
./gradlew :app:assembleBasicDebug
```

Release builds read `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`,
`RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` as Gradle properties.

## Credits & license

All the hard work (NPU inference, model conversion, the app itself) is
[xororz](https://github.com/xororz)'s and the Local Dream contributors'. Please
support the original project.

Same license as upstream:
[CC BY-NC 4.0](LICENSE). Attribution is required and commercial use is not allowed.

Not affiliated with Local Dream or MediBang.

---

<details>
<summary><b>Original Local Dream README</b></summary>

<div align="center">

# Local Dream <img src="./assets/icon.png" width="32" alt="Local Dream">

**Android Stable Diffusion with Snapdragon NPU acceleration**  
_Also supports CPU/GPU inference_

<img src="./assets/demo1.jpg" alt="App Demo" width="800">

</div>

## About this Repo

This project is **now open sourced and completely free**. Hope you enjoy it!

If you like it, please consider [sponsor](#-support-this-project) this project.

> [!NOTE]
> Currently focused on SD1.5 and SDXL models. SD2.1 is no longer maintained due to poor quality and limited popularity.
>
> **NPU support:**
>
> - SD1.5 models are supported on Snapdragon NPUs with Hexagon V68 architecture or newer.
> - SDXL models are supported on Snapdragon 8 Gen 3 and newer devices.
>
> CPU/GPU inference supports SD1.5 and imported SDXL MNN packages. Large SDXL
> models require substantially more memory than SD1.5.
>
> You can join our [telegram group](https://t.me/local_dream) for discussion or help with testing.

## User Guide

For certain reasons, all guides and documentation have been moved to [Guide Site](https://ld-guide.chino.icu).

## Credits & Acknowledgments

Local Dream is built on top of many excellent open-source projects. Sincere thanks to all the authors and contributors whose work made this project possible.

### C++ Libraries

- **[Qualcomm QNN SDK](https://www.qualcomm.com/developer/software/qualcomm-ai-engine-direct-sdk)** - NPU model execution
- **[alibaba/MNN](https://github.com/alibaba/MNN/)** - CPU model execution
- **[happyyzy/stable-diffusion.cpp](https://github.com/happyyzy/stable-diffusion.cpp)** - Deep performance optimizations for on-device DiT inference
- **[xtensor-stack](https://github.com/xtensor-stack)** - Tensor operations & scheduling
- **[mlc-ai/tokenizers-cpp](https://github.com/mlc-ai/tokenizers-cpp)** - Text tokenization
- **[yhirose/cpp-httplib](https://github.com/yhirose/cpp-httplib)** - HTTP server
- **[nothings/stb](https://github.com/nothings/stb)** - Image processing
- **[facebook/zstd](https://github.com/facebook/zstd)** - Model compression
- **[nlohmann/json](https://github.com/nlohmann/json)** - JSON processing

### Android Libraries

- **[square/okhttp](https://github.com/square/okhttp)** - HTTP client
- **[coil-kt/coil](https://github.com/coil-kt/coil)** - Image loading & processing
- **[MoyuruAizawa/Cropify](https://github.com/MoyuruAizawa/Cropify)** - Image cropping
- **AOSP, Material Design, Jetpack Compose** - UI framework

### Models

- **[CompVis/stable-diffusion](https://github.com/CompVis/stable-diffusion)** and all other model creators
- **[xinntao/Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN)** - Image upscaling
- **[Kim2091/UltraSharpV2](https://huggingface.co/Kim2091/UltraSharpV2)** - Image upscaling
- **[bhky/opennsfw2](https://github.com/bhky/opennsfw2)** - NSFW content filtering

---

## 💖 Support This Project

If you find Local Dream useful, please consider supporting its development:

### What Your Support Helps With:

- **Additional Models** - More AI model integrations
- **New Features** - Enhanced functionality and capabilities
- **Bug Fixes** - Continuous improvement and maintenance

<a href="https://ko-fi.com/xororz">
    <img height="36" style="border:0px;height:36px;" src="https://storage.ko-fi.com/cdn/kofi2.png?v=3" border="0" alt="Buy Me a Coffee at ko-fi.com" />
</a>
<a href="https://afdian.com/a/xororz">
    <img height="36" style="border-radius:12px;height:36px;" src="https://pic1.afdiancdn.com/static/img/welcome/button-sponsorme.jpg" alt="在爱发电支持我" />
</a>

Your sponsorship helps maintain and improve Local Dream for everyone!

</details>
