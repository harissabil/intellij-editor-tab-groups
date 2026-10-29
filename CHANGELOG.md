<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Editor Tab Groups Changelog

## [Unreleased]

### Fixed

- Group label drawn over the wrong tab, or at the left edge, when the tab bar scrolls with many open tabs

## [0.1.0-beta] - 2026-10-29

### Added

- Named, colored tab groups in the editor tab bar: Add Tab to New Group, Add Tab to Group, Remove from Group
- Collapse and expand a group by clicking its label; collapsed tabs stay open, and the selected tab stays visible until you switch away
- Drag tabs into and out of groups, and drag a group's label to move the whole group
- Group panel on the label's context menu: rename, color, copy file paths, ungroup, save and close, delete
- Reopen Saved Tab Group for groups that were saved and closed
- Separate groups per editor split, kept across IDE restarts
