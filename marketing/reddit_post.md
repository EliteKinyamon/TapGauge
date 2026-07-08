# Reddit draft — r/RVLiving, r/GoRVing, r/gorving

> Tone note: these communities downvote anything that reads like an ad. Lead with
> the shared problem in their words, be specific, be upfront about limitations,
> no superlatives. Post as a maker sharing a thing, not a brand.

---

**Title:** I got sick of my black/grey tank sensors lying to me, so I made an app that reads the level by tapping the tank with your phone

**Body:**

Like probably everyone here, my holding-tank sensors are basically decoration.
The black tank reads 2/3 full for a month, then jumps to empty. Grey is no
better. It's the classic problem — gunk coats the probes and they just stop
telling the truth. I know you can buy external sensor kits, but I didn't want to
spend $100+ and stick more hardware on the rig.

So I built **TapGauge**. It uses only your phone's mic — no sensor, nothing to
install. You tap the side of the tank with a knuckle and it reads the pitch. A
tank changes pitch as it fills, same as blowing across a bottle, and the app
turns that into a rough fill %.

Being straight about how it works, because there's no magic:

- **It has to be calibrated per tank.** There's no universal formula — every
  tank rings differently.
- **Fresh water:** dead simple — tap when you fill, tap when the pump sputters.
- **Grey/black:** you tap "just dumped" at the dump station for the 0% point, and
  for the in-between levels you do a one-time "driveway calibration" at home with
  **clean water** — add a few known amounts (jugs or a timed hose), tap after
  each, then dump and rinse before you head out. Yes, it's a bit of a ritual, but
  it's a one-time thing and it's the only honest way to learn the mid-levels.

**What it needs:** an Android phone and about 15 minutes in the driveway once.
**What it is:** a convenience estimate, not a certified gauge. Temperature swings
can shift it (it'll tell you to recalibrate). It's all offline — no account, no
internet, recordings never leave your phone.

Not trying to sell anyone anything — I mostly wanted to stop guessing at the
dump station. Happy to answer questions about how the tapping/calibration
actually works, and genuinely interested in whether it holds up on tank shapes
other than mine.

---

**Comment-reply bank (pre-written honest answers):**
- *"Does it really work through the tank wall?"* — It's not reading through the
  wall like ultrasonic; it's listening to how the whole tank + air-space resonates
  when you tap it, which shifts with the liquid level. That's why it needs
  calibrating per tank rather than shipping with a fixed formula.
- *"Why fill my black tank with water?"* — Only clean water, only for the one-time
  driveway calibration, and you dump + rinse right after. It's just to teach the
  app the mid-levels safely — you never calibrate with waste.
- *"Battery/privacy?"* — Mic only runs the couple seconds you're actively
  measuring, never in the background. No internet permission at all.
