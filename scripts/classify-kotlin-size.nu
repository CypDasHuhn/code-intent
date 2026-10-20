#!/usr/bin/env nu

const suspicious = 200
const extreme = 400

def main [root: path = "."] {
  cd $root

  let base = (pwd)
  let rows = (
    glob **/*.kt --exclude [ **/build/** **/.gradle/** **/.git/** ]
    | each {|file|
        { file: ($file | path relative-to $base), lines: (open --raw $file | lines | length) }
      }
    | where lines >= $suspicious
    | each {|row|
        let class = if $row.lines >= $extreme { "EXTREME" } else { "SUSPICIOUS" }
        $row | insert class $class
      }
    | sort-by lines --reverse
  )

  if ($rows | is-empty) {
    print "No suspicious or extreme Kotlin files."
  } else {
    $rows | select class lines file
  }
}
