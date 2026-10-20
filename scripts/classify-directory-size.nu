#!/usr/bin/env nu

def main [root: path = "."] {
  cd $root
  let base = (pwd)

  let rows = (
    glob **/* --no-file --no-symlink --exclude [ **/build/** **/.gradle/** **/.git/** **/.*/** ]
    | each {|dir|
        let entries = (ls $dir)
        let files = ($entries | where type == file | length)
        let dirs = ($entries | where type == dir | length)
        {
          dir: ($dir | path relative-to $base)
          files: $files
          dirs: $dirs
          entries: ($files + $dirs)
        }
      }
    | where {|row| ($row.files == 1 and $row.dirs == 0) or $row.files > 5 or $row.entries > 8 }
    | each {|row|
        let class = if $row.files > 5 or $row.entries > 8 { "OVERCROWDED" } else { "SINGLE" }
        $row | insert class $class
      }
    | sort-by entries --reverse
  )

  if ($rows | is-empty) {
    print "No single-file or overcrowded directories."
  } else {
    $rows | select class files dirs entries dir
  }
}
