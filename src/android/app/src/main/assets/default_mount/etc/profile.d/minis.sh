# MinisApp shell configuration
# Loaded by /etc/profile via the profile.d mechanism (login shells only).

# Prompt parity with iOS — `root@minis:/var/minis#`. iOS bakes the
# literal "minis" into PS1 (deps/prepare_alpine_rootfs.sh) rather than
# relying on \h, so the prompt is stable regardless of what /etc/hostname
# happens to contain. We do the same on Android so a fresh install
# matches without needing a rootfs rebuild.
export PS1='\u@minis:\w\$ '

# Enable ash command history with arrow keys
export HISTFILE="$HOME/.ash_history"
export HISTSIZE=1000

# Point ENV to .ashrc so interactive ash picks up line-editing config
export ENV="$HOME/.ashrc"

# Default pager — less is standard on Alpine; keep explicit for scripts
# that probe $PAGER.
export PAGER=less

# URL interception: $BROWSER is also seeded directly into every process
# envp via PRootKernel.customEnvironment so non-login shells (which never
# source profile.d) still see it. The xdg-open / sensible-browser /
# www-browser / x-www-browser / gnome-open / kde-open wrappers live as
# real files in default_mount/usr/local/bin/ and are overlaid on every
# boot.
export BROWSER=/usr/local/bin/minis-open

# PRoot's link2symlink extension creates .l2s.* sentinel files alongside
# every hardlinked file. uv's default `hardlink` mode tries to re-link these
# sentinels when populating site-packages, which PRoot rejects with EPERM.
# Force uv to symlink package files instead — the sentinels are then never
# touched as link sources. Reported as openminis/openminis#7.
export UV_LINK_MODE=symlink

# openminis/openminis#375: same failure family as UV_LINK_MODE above, via Git.
# `git gc` / `repack` / `clone --local` create objects with link(), which
# link2symlink turns into .l2s.* sentinels written INTO .git/objects/pack/.
# Git chmods that directory to 0555, so the sentinel bookkeeping (which runs
# as the real uid, not the faked root) fails and the pack files become
# undeletable. Copying instead of hardlinking keeps Git off that path
# entirely. Costs disk on a local clone; sandbox repos are small.
#
# Only affects NEW operations -- it cannot repair already-stuck files.
git config --system core.createObject copy 2>/dev/null || true
