# ReelSmith

Turn a long interview plus a pile of wide b-roll rushes into vertical reels,
without the subject ever falling out of frame.

Built for the shape of job where you have one 16:9 sit-down interview, several
minutes of 16:9 rushes holding dozens of usable shots, and you need 45–60s 9:16
cuts in which the interview voiceover runs unbroken while b-roll carries the
picture.

## The pieces

| | |
|---|---|
| `shots.py` | splits rushes into individual shots and scores each one |
| `reframe.py` | 16:9 → 9:16 with the subject tracked, and a fallback that cannot crop them out |
| `colab/ReelSmith_Colab.ipynb` | runs the pipeline in Colab against footage already in Drive |

## Keeping the subject in frame

A 9:16 window cut from a 16:9 frame is only 32% of its width. Two players at
opposite ends of a padel court cannot both be large in that window — so the
tool does not pretend otherwise. It picks a layout per shot:

- **crop** — the window tracks the subject. Chosen only when the subject's box
  fits inside it on *every* frame, with a margin.
- **fill** — the whole wide frame, letterboxed over a blurred, scaled copy of
  itself. Nothing is cropped, so the subject cannot leave. This is the
  guarantee, and it is the look most vertical sports edits already use.
- **hybrid** — crop for the runs of frames where it fits, fill for the runs
  where it does not. Runs shorter than 0.6s are absorbed into their neighbour,
  and a shot that would still flip more than once every two seconds is given a
  single layout instead, so the change never reads as a glitch.

The subject track is built from detectors bundled with OpenCV — HOG people,
Haar frontal-face, profile-face and upper-body, and a dense optical-flow
centroid when none of them fire. No weights are downloaded, so it runs in a
sandbox with no access to model hosts.

## Which person

On a court there is more than one person, and "the biggest detection" will
happily follow the wrong one. `--subject match` keeps the *interviewee*: it
signs the torso of a reference frame as a hue/saturation histogram and, among
every candidate in a frame, takes the best match. Below `--match-floor` the
subject is reported absent rather than confused with someone else, so shots
they are not in can be filtered out instead of silently mistracked.

One reference frame is not enough. Light swings between setups over a shoot and
saturation swings with it, so a signature taken in flat light scores the same
person badly when they are backlit — measured, not assumed:

| Shot | Who is in it | One reference | Six references |
|---|---|---|---|
| 0.00–3.04s | subject, from behind | 0.102 ✗ | **0.569** ✓ |
| 3.04–18.88s | subject | 0.662 ✓ | 0.895 ✓ |
| 18.88–22.28s | another player, other kit | 0.053 ✓ | 0.062 ✓ |
| 22.28–27.08s | subject | 0.530 ✓ | 0.626 ✓ |
| 27.08–29.84s | another player | 0.082 ✓ | 0.185 ✓ |
| 29.84–37.40s | subject | 0.746 ✓ | 0.779 ✓ |
| 37.40–39.76s | a third person | 0.112 ✓ | 0.175 ✓ |
| 39.76–41.24s | hands, no face | 0.091 ✓ | 0.121 ✓ |
| 41.24–56.68s | subject, backlit | 0.132 ✗ | **0.767** ✓ |
| 56.68–60.96s | subject | 0.385 ✓ | 0.475 ✓ |

One reference got 10 of 12 shots right and missed both hard ones. Six
references spread across the clip got **12 of 12**, and left a clear gap —
0.185 for the closest wrong answer against 0.475 for the weakest right one —
which is why the floor sits at 0.30. Pass several `--ref-at` times.

Raw detections are jumpy, so the track is conditioned before it drives
anything: gaps between detections are linearly interpolated, the result is
smoothed with a one-second Hann window, and the window's velocity is clamped to
6 source-pixels per frame. On a 500-frame test clip that took the mean
frame-to-frame movement of the crop centre from 31.8px to 3.1px — the
difference between a servo twitch and an operator pan.

### Measured

On a 1599-frame handheld sports clip, using only the bundled detectors:

```
detected      1300/1599 = 81.3%
              face 899 · flow 268 · hog 123 · upper 10
```

On the 500-frame synthetic clip with a subject crossing nearly the full frame
width — deliberately harder than real footage:

```
detection_rate            0.996
frames_subject_would_clip 203      -> rendered as fill
pan_px_per_frame_max      6.0      -> velocity clamp holding
```

81% detection is not a guarantee on its own, which is why the guarantee does
not rest on it. Every frame's window is checked against the subject box before
anything is written, and any frame that fails is rendered as fill rather than
cropped. `--strict` turns a forced crop that would clip into a non-zero exit
instead of a bad render.

## Cut points

Where to cut b-roll is not a guess. The editor's own caption changes in a
reference reel were compared against pauses found by `silencedetect`:

| Reference cut | Detected pause | Δ |
|---|---|---|
| 3.84s | 3.78s | 0.06s |
| 6.28s | 6.46s | 0.18s |
| 9.68s | 9.64s | 0.04s |
| 13.20s | 13.15s | 0.05s |
| 18.88s | 18.88s | 0.00s |

Speech pauses are where a human editor cuts. Detecting them gets within about
0.07s, which is inside a single frame at 25fps for most of them.

## Usage

```bash
# split rushes into shots, score them, write a thumbnail per shot
python3 shots.py rush_01.mov rush_02.mov --out shots.json --thumbs thumbs/

# reframe one shot to 9:16, tracking the interviewee and no one else
python3 reframe.py --input rush_01.mov --out shot_004.mp4 \
    --start 41.2 --end 56.7 --size 1080x1920 --mode hybrid \
    --subject match --ref-clip interview.mov --ref-at 6,24,46 \
    --report shot_004.json --qc shot_004_qc.png
```

Drop `--subject match` and its reference flags to track whoever fills most of
the frame instead — right for shots with only one person in them.

`--qc` writes a one-frame-per-second contact sheet of the render, which is the
fastest way to confirm by eye that nobody left the frame.

## Requirements

```
ffmpeg (with libass)          rendering and caption burn-in
opencv-python-headless 4.10   detectors; OpenCV 5 dropped HOGDescriptor
scenedetect                   shot boundaries
numpy
```

`melt` (MLT) and `opentimelineio` are optional and only needed for handing an
editable timeline back — MLT writes a project Kdenlive opens, OTIO writes EDL
and FCP XML that Premiere and Resolve import, so the finished cut can be
re-rendered from the original media rather than the proxies.

## Why Colab

Rushes run 70–400 MB per file. That is past what a chat upload or the Drive
connector will carry (the connector caps at 10 MB per file), and transcription
needs model weights from hosts a sandbox may not reach. The notebook sidesteps
both: it mounts Drive, writes proxies under the connector's ceiling, runs
Whisper for word-level timing, and leaves everything back in Drive.
