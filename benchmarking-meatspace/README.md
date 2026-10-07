# Benchmarking meatspace

The kernel has a conformance suite, a GC stress mode and a progress chart. The people writing it
had nothing. Until now (#111). Welcome to calibration.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/JackFurton/who-would-build-a-kernel-in-java/metrics/calibration-dark.svg">
  <img alt="Calibration: every contributor's level from merged code, with work in review and a 30-day projection" src="https://raw.githubusercontent.com/JackFurton/who-would-build-a-kernel-in-java/metrics/calibration-light.svg">
</picture>

And the race everyone actually cares about: who's the alpha among contributors from outside the
founding team.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/JackFurton/who-would-build-a-kernel-in-java/metrics/external-race-dark.svg">
  <img alt="The external race: impact over time for contributors outside the founding team, projected 30 days out" src="https://raw.githubusercontent.com/JackFurton/who-would-build-a-kernel-in-java/metrics/external-race-light.svg">
</picture>

CI recalibrates on every push to master. Run it yourself with
`java benchmarking-meatspace/Calibration.java` (add a directory to get the SVGs).

## How calibration works

The committee reads two things: the git history of master, and open PRs on GitHub. Anyone who has
merged a PR is calibrated, collaborator or not; anyone with an open, non-draft PR shows up the
moment they open it, with their work counted as in review.

- **Size is logarithmic.** Each area a PR touches scores `weight × log2(1 + lines changed there)`.
  The 3,000th line of a change matters less than the 30th, so 20 lines in the right place can beat
  4,000 in the wrong one.
- **Where it lands matters more than how much.** Runtime and GC 3.0, the compiler 2.5, the kernel
  2.0, JavaScript 2.0, `java.lang`/`java.util` 1.5, tests 1.0, docs and infra 0.7.
- **Performance work counts triple.** A PR whose title says it's about speed targets the paths
  everything else runs through. Mostly, this rule exists for one person.
- **Shipping tests with code earns 1.2×.**
- **Levels come from merged impact only:** L3 Software Engineer, L4 Software Engineer II from 25,
  L5 Senior from 60, L6 Staff from 150, L7 Senior Staff from 350, L8 Principal from 800. Open PRs
  count toward your projection, not your level. You get leveled on what shipped, not on what's in
  review, which is also how real companies work.
- **Your specialty is wherever your impact lands.** Mostly JS makes you a JavaScript engineer;
  mostly perf, a performance engineer. Nobody picks their own title.
- **Projections** add your open PRs to your pace (impact per day since your first merge, over at
  least a week, because one good day isn't a trend), 30 days out. The promo ETA comes from the same
  numbers.
- **The founder is not calibrated.** JackFurton sets the calibration.
- **The external race** is everyone except the founder and the founding team (dkempner and
  Firebathero, who knew him before the repo did). The crown goes to whoever's projection is
  highest.

Not counted: reviews, issue comments, or vibes.

Written reviews happen in review cycles. Anyone calibrated since the last cycle is listed under the
chart as review pending until the next one.

## Performance reviews

**JackFurton**, Founder & CEO, unleveled. Owns the repo, wrote the branch protection rules, then
granted himself the only exemption from them. Commissioned this calibration and exempted himself
from it as well. The committee has reviewed the arrangement and found it to be in the best
interests of the company. Compensation: equity.

**dkempner**, Senior Software Engineer, JavaScript. His first contribution to the repo was
proposing that a Java PR be closed and rewritten in JavaScript. He then wrote a JavaScript
compiler, then a JavaScript-to-Java translator, and then put JavaScript in the kernel, which is
now a Java kernel that runs shell commands written in JavaScript. Requested changes on a
colleague's PR for "committing json to inflate contributions", then opened the issue asking to be
ranked. Top of the calibration by merged impact, and he'd like everyone to know he asked for it.

**Firebathero**, Senior Software Engineer, Performance. Shipped 20 lines of code and a
6,700-character PR description with an Amdahl's law section, seven interleaved trials and a
medians table. Profiled a kernel that was three days old. Tried to land a NumPy dependency in a
Java kernel and was talked out of it in review, which he took with the grace of a man who had
already made boot 19% faster. Single-handedly the reason the performance multiplier is 3 and
not 2.

**Kethankumar99**, Software Engineer II, Device Drivers. Arrived from the internet, claimed an
issue, made the arrow keys work, then took a round of review, added `StringBuilder.insert` with
conformance tests, and came back with it all done. Every time anyone moves a cursor in the Duke
shell, that's him. First holder of the external crown.

**senthilkumar-r**, Software Engineer, Device Drivers, and the first person from outside who ever
shipped code here. Went through three senior reviewers and a redesign for a method that prints
a number in hex, and shipped a cleaner API than anyone asked for. His promo packet is in progress:
the L4 ETA is on the chart above, and the committee checks it daily.
