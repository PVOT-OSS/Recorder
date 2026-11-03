<!--
SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
SPDX-License-Identifier: Apache-2.0
-->

# Privacy Policy — Recorder

_Effective 4 November 2026_

Recorder (package `org.prauga.recorder`) is a sound recorder based on the LineageOS Recorder,
published by Saalim Quadri for PVOT-OSS. This policy explains what the app does with your data.

**In short: everything you record stays on your device. The app doesn't collect, upload, share or
sell any personal data. It has no accounts, no ads, no analytics and no tracking. Transcription,
speaker labels and sound labels run entirely on your device.**

## What the app keeps on your device

- **Your recordings:** saved as audio files in your device's shared storage, in
  `Recordings/Sound records`. Like any audio in shared storage, other apps you have allowed to read
  your audio files can see them. While you record, the audio is first written to the app's own
  storage and moved there when you stop. Deleted recordings go to Android's trash, from which you
  can restore them for 30 days before Android removes them for good.
- **What the app works out from your recordings:** transcripts (with word timings), speaker names
  you give, sound labels such as music or laughter, a waveform for drawing each recording, and the
  favourites and labels you add.
- **Where a recording was made, only if you turn on location tagging:** the coordinates and a
  place name, stored with the recording and used in its default file name.
- **Speech and sound models you download** (see below).
- **Your settings:** audio quality, stereo, clear voice, microphone, transcription, theme, playback
  speed and skip silence.

None of this leaves your device, except:

- **When you share it.** Sharing a recording, a transcript or a video clip hands it to the app you
  pick, under that app's terms. If another app asks Recorder for a recording (for example to attach
  one to a message), it receives it only after you confirm.
- **Through Android's own backup, if you have turned it on.** The app's settings and its database
  (transcripts, labels, speaker names, favourites and, if you used location tagging, places) and
  waveforms may then be backed up to your Google account, under Google's terms. The recordings
  themselves are not part of the app's backup; downloaded models are not backed up either.

Clearing the app's storage or uninstalling it deletes everything above except your recordings,
which stay in shared storage until you delete them.

## The microphone

The app records only while you are recording: from the app, its Quick Settings tile, its widget
or shortcut, or an automation app that asks it to start. A notification is shown for the whole
recording, and Android shows its microphone indicator. Live transcription listens to the same
audio, on your device. Other apps that ask Recorder to start or stop recording never receive the
audio.

## What the app downloads

The app needs the internet permission only to download the models you choose in **Settings →
Languages and models**. Nothing is downloaded unless you ask for it, and by default only over
Wi-Fi. Downloads go through Android's download manager to:

| Service | What for |
|---|---|
| `alphacephei.com` | Speech recognition models for each language, and the speaker label model ([Vosk](https://alphacephei.com/vosk/), Apache-2.0) |
| `storage.googleapis.com` | The sound label model ([YAMNet](https://www.kaggle.com/models/google/yamnet), Apache-2.0), hosted by Google |

These requests send nothing about you beyond what any app or browser sends to download a file:
your IP address, the time, and basic technical details such as the user agent. The services that
receive them may log them under their own policies. No recording, transcript or other data from
the app is ever sent.

## Location

Location tagging is off unless you turn it on. When it's on, the app reads the location Android
last knew when you start a recording; it doesn't track you. To turn the coordinates into a place
name, it uses Android's geocoder. On devices with Google Play services this sends the coordinates
to Google to look up the name, under Google's terms; on devices without a geocoder the app shows
the coordinates instead.

## Permissions

- `RECORD_AUDIO`, to record.
- `POST_NOTIFICATIONS`, to show the recording, playback, transcription and download notifications.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` and
  `FOREGROUND_SERVICE_DATA_SYNC`, to keep recording, playing back and transcribing when the app is
  in the background.
- `WAKE_LOCK`, inherited from the LineageOS Recorder; this version doesn't use it.
- `ACCESS_COARSE_LOCATION` and `ACCESS_FINE_LOCATION`, only if you turn on location tagging.
- `INTERNET`, only to download the models above.

## Children

The app is not directed at children and collects no data from anyone, children included.

## Changes

If the app starts handling data differently, this policy will be updated before that version is
released, with a new effective date at the top.

## Contact

Questions about this policy: danascape@gmail.com, or open an issue at
https://github.com/PVOT-OSS/Recorder/issues.
