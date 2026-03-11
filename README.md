# JPEG Compression + Optimization

## Run Instructions

```bash
java ImageDisplay.java <img>.rgb <M> <Q> <B>
```

**Example:**

```bash
java ImageDisplay.java img.rgb 2 -1 1.5
```

> **Note:** When `M=2`, it takes 1–2 minutes for the output to appear. It will show. Please wait.

---

## Adaptive NxN Algorithm

### 1) Compute Variance Threshold

The idea is simple: if the variance within a block is high, that region is detailed and needs smaller blocks. If variance is low, a larger block will suffice.

Rather than using a fixed threshold, the threshold is computed dynamically for each image. I felt that a fixed threshold is unreliable - it depends on the image content:

- Scan the image in 32×32 tiles and compute the luma variance of each tile.
- Sort all variances in ascending order and take the **70th percentile** as the threshold.

**Why the 70th percentile?** For each image, only the top 30% most-complex blocks get subdivided. This ensures strong compression overall while reserving fine-grained blocks for the areas that genuinely need them.

---

### 2) Quadtree Subdivision

Block sizes are selected from **{2, 4, 8, 16, 32}** depending on local image content.

Starting at block size 32, the algorithm computes the variance of each block and recursively subdivides if the variance exceeds the threshold:

```
function setAdaptiveBlockSize(x, y, blockSize):
    compute luma variance of block at (x, y)

    if variance > threshold AND blockSize / 2 >= 2:
        split into four sub-blocks of size (blockSize / 2)
        recurse on each sub-block
    else:
        assign blockSize to every pixel in this block
```

Recursion continues until the block's variance falls below the threshold or the minimum block size (2×2) is reached.

---

### 3) Why this works

I am being greedy here - I initially start of with block size 32. If variance exceeds the threshold, then only I divide it recursively till variance is lesser than threshhold or block cannot be further divided. This favors the largest blocks possible improving compression without compromising quality.

### 4) Auto-Compute Q (when `Q = -1`)

To find the best value of Q for a target bitrate `B`, the compression is simulated via binary search:

- Search over Q in **[0, 30]**.
- For each candidate Q, encode the image, write a temporary `.DCT` file, gzip-compress it in memory, and check whether the resulting bits-per-pixel meets the target.
- Adjust the search range accordingly. If `bpp <= B`, try a lower Q (better quality); otherwise try a higher Q (more compression).

```
binary search Q in [0, 30]:
    encode image with candidate Q
    write temporary .DCT file
    gzip-compress in memory
    compressed_bpp = gzip_bytes * 8 / (width * height)

    if compressed_bpp <= B  →  try lower Q (better quality)
    else                    →  try higher Q (more compression)

delete temporary file, return best Q
```
