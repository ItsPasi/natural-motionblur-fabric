<p align="center">
    <img src="https://github.com/ItsPasi/natural-motionblur-fabric/blob/1.20.6-fabric/docs/blur%20icon%20320px.png" />
    
# Natural Motion Blur

This mod aims to mimic the way human vision motion blur works by blending frames together, reducing stroboscopic effects and making motion appear more fluent.

It is recommended to use the default strength value (1).

The config GUI can be accessed via these commands: ```/motionblur``` ```/mb```

![thumbnail](https://github.com/ItsPasi/natural-motionblur-fabric/blob/1.20.6-fabric/docs/blur%20thumbnail.png?raw=true)

## Feature List
- Blur Toggle - Including adjustable Keybind (B)
- Refresh Rate Scaling - Adjusts blur strength based on FPS and refresh rate
- Blur Strength Adjustment
- Multiple Blur Methods
  - Velocity Based (Default)
  - Frame Blending
  - Hybrid Blending
  - Accumulation Max
  - Accumulation Mix
- OBS Recording Output

## OBS Recording Output
Since version 1.4.0 of the mod you now have the ability to directly output a frame blended game in OBS via a spout sender. This is a good option for people who can't or don't want to record in 240 FPS or more to create smooth gameplay videos. You can now simply use this option and record at a normal FPS rate.

To set this up follow these instructions:  
1. Download the Spout2 Plugin Installer from https://github.com/Off-World-Live/obs-spout2-plugin
2. Run the installer, then enable the plugin via OBS > Tools > Plugin Manager
3. In OBS, add a new Source and select "Spout2 Capture"
4. Enable the in-game option
   
Instructions can also be viewed in the in-game GUI.

## Motionblur Example

![example1](https://github.com/ItsPasi/natural-motionblur-fabric/raw/1.20.6-fabric/docs/blur%20example.png)

[![Video Example](https://img.youtube.com/vi/Dxp989uGeBQ/maxresdefault.jpg)](https://www.youtube.com/watch?v=Dxp989uGeBQ "Motion Blur Example")
_This mod was made with the help of IMS, What42Pizza and Romain H._
