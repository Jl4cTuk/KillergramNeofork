<p align="center">
  <img src="art/icon.svg" width="150" alt="Killergram Neofork">
</p>

# Killergram Neofork

Make Telegram usable again!

Xposed module aimed to enhance Telegram without needing to modify the original app.

## ⬇️ Downloads

Download the latest fork release [here.](https://github.com/Jl4cTuk/KillergramNeofork/releases/latest)

## Installation requirements

* A rooted Android device
* An **Xposed API provider** - we recommend [Vector](https://github.com/JingMatrix/Vector) 

## Features

#### Appearance

* Force system fonts
* Replace the `edited` label with a monochrome pencil icon
* Show seconds in timestamps (`HH:mm:ss`)
* Disable subscriber/member count rounding
* Replace the app title with your account first name or custom text, with optional centered title

#### Navigation

* Hide stories
* Hide the floating create button in the chat list
* Hide individual forum topics from the topic's long-press menu
* Show chats that belong to a folder only in that folder, not in All or the Archive
* Hide the Contacts tab from the bottom bar
* Don't open Contacts by swiping past the last folder
* Hide the channel action bar (mute/discussion/gifts/join)
* Hide the side share button on channel posts

#### Camera

* Increase video note bitrate
* Increase video note resolution
* Default camera and video notes to the rear camera
* Keep video note zoom after lifting your fingers
* Fix video note audio/video sync

#### Chats & media

* Default media sending to **HD** in the photo picker
* Keep the attach camera tile blurred until tapped
* Disable audio playback triggered by volume buttons
* Disable Telegram's built-in notification delay
* Raise recent stickers and emoji limits

#### Privacy & profile

* Show a copyable user ID on profile screens
* Allow forwarding from anywhere*

#### Restrictions & cleanup

* Remove sponsored messages*
* Hide paid **Stars** reactions
* Hide in-app update prompts

#### Experimental

* Force local Premium* for local UI checks only; server-side Premium features still do not unlock
* Anti-recall deleted messages* by keeping deleted content in chat history locally

\* - Potentially breaking Telegram's ToS, be careful!

## Supported clients

In theory: Any client that is a fork of original [Telegram](https://github.com/DrKLO/Telegram) app.

Has been tested on:
* **Telegram** - org.telegram.messenger(.web), version `13.1.0`

## FAQ

* **'Chat translation spoof for non-premium?'** - Impossible, as Telegram apparently rate-limits users based on some serverside Premium check.
* **'Auto-update?'** - Use [Obtainium.](https://github.com/ImranR98/Obtainium)
* **'LSPatch support?'** - There is no support now.
