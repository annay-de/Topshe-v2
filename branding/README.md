# App icon

The app icon is made from **`icon.png`** in this folder, every time the app is built.

**To change it (from the GitHub website or app):**
1. Open this `branding` folder in GitHub, then **Add file → Upload files**.
2. Upload your picture named exactly `icon.png` (a `.jpg` works too if you name it `icon.jpg`
   and delete `icon.png`). Square pictures look best, 512×512 or bigger.
3. Press **Commit changes**. About 10 minutes later a new app build appears under Releases with your icon.

**Optional fine-tuning** – create `icon.json` here:
```json
{"background": "#F3E7CF", "zoom": 1.0}
```
- `background`: colour around your picture (default: taken from the picture's edges)
- `zoom`: above 1 makes the picture bigger (round icons cut more of its edges), below 1 smaller

`owl_source.svg` is the source drawing of the default owl.
