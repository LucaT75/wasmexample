(module
  (memory (export "memory") 2 2)
  (data (i32.const 0) "factor1factor2")
  (data (i32.const 32) "{\"result\":")

  (func (export "alloc") (param $size i32) (result i32)
    (drop (local.get $size))
    (i32.const 4096))

  (func $skip_whitespace (param $cursor i32) (param $end i32) (result i32)
    (local $byte i32)
    (block $done
      (loop $spaces
        (br_if $done (i32.ge_u (local.get $cursor) (local.get $end)))
        (local.set $byte (i32.load8_u (local.get $cursor)))
        (br_if $done
          (i32.and
            (i32.ne (local.get $byte) (i32.const 32))
            (i32.and
              (i32.ne (local.get $byte) (i32.const 9))
              (i32.and
                (i32.ne (local.get $byte) (i32.const 10))
                (i32.ne (local.get $byte) (i32.const 13))))))
        (local.set $cursor (i32.add (local.get $cursor) (i32.const 1)))
        (br $spaces)))
    (local.get $cursor))

  (func $parse_factor
    (param $input i32) (param $length i32) (param $key i32) (param $key_length i32)
    (result i64)
    (local $position i32) (local $index i32) (local $matched i32)
    (local $cursor i32) (local $end i32) (local $negative i32)
    (local $value i64)
    (local.set $end (i32.add (local.get $input) (local.get $length)))
    (block $not_found
      (loop $scan
        (br_if $not_found
          (i32.gt_u (local.get $position)
            (i32.sub (local.get $length) (local.get $key_length))))
        (local.set $matched (i32.const 1))
        (local.set $index (i32.const 0))
        (block $compare_done
          (loop $compare
            (br_if $compare_done (i32.ge_u (local.get $index) (local.get $key_length)))
            (if
              (i32.ne
                (i32.load8_u (i32.add (local.get $input)
                  (i32.add (local.get $position) (local.get $index))))
                (i32.load8_u (i32.add (local.get $key) (local.get $index))))
              (then
                (local.set $matched (i32.const 0))
                (br $compare_done)))
            (local.set $index (i32.add (local.get $index) (i32.const 1)))
            (br $compare)))
        (if (local.get $matched)
          (then
            (local.set $cursor
              (i32.add (local.get $input)
                (i32.add (local.get $position) (local.get $key_length))))
            (if (i32.eq (i32.load8_u (local.get $cursor)) (i32.const 34))
              (then (local.set $cursor (i32.add (local.get $cursor) (i32.const 1)))))
            (local.set $cursor (call $skip_whitespace (local.get $cursor) (local.get $end)))
            (if (i32.eq (i32.load8_u (local.get $cursor)) (i32.const 58))
              (then
                (local.set $cursor (i32.add (local.get $cursor) (i32.const 1)))))
            (local.set $cursor (call $skip_whitespace (local.get $cursor) (local.get $end)))
            (local.set $negative (i32.const 0))
            (if (i32.eq (i32.load8_u (local.get $cursor)) (i32.const 45))
              (then
                (local.set $negative (i32.const 1))
                (local.set $cursor (i32.add (local.get $cursor) (i32.const 1)))))
            (local.set $value (i64.const 0))
            (block $digits_done
              (loop $digits
                (br_if $digits_done (i32.ge_u (local.get $cursor) (local.get $end)))
                (br_if $digits_done
                  (i32.or
                    (i32.lt_u (i32.load8_u (local.get $cursor)) (i32.const 48))
                    (i32.gt_u (i32.load8_u (local.get $cursor)) (i32.const 57))))
                (local.set $value
                  (i64.add
                    (i64.mul (local.get $value) (i64.const 10))
                    (i64.extend_i32_u
                      (i32.sub (i32.load8_u (local.get $cursor)) (i32.const 48)))))
                (local.set $cursor (i32.add (local.get $cursor) (i32.const 1)))
                (br $digits)))
            (if (local.get $negative)
              (then (local.set $value (i64.sub (i64.const 0) (local.get $value))))
              (else (nop)))
            (return (local.get $value))))
        (local.set $position (i32.add (local.get $position) (i32.const 1)))
        (br $scan)))
    (i64.const 0))

  (func (export "diff") (param $input i32) (param $length i32) (result i64)
    (local $value i64) (local $magnitude i64) (local $negative i32)
    (local $cursor i32) (local $digit_count i32) (local $output_length i32)
    (local.set $value
      (i64.sub
        (call $parse_factor (local.get $input) (local.get $length) (i32.const 0) (i32.const 7))
        (call $parse_factor (local.get $input) (local.get $length) (i32.const 7) (i32.const 7))))
    (memory.copy (i32.const 8192) (i32.const 32) (i32.const 10))
    (local.set $cursor (i32.const 8202))
    (local.set $negative (i64.lt_s (local.get $value) (i64.const 0)))
    (if (local.get $negative)
      (then
        (i32.store8 (local.get $cursor) (i32.const 45))
        (local.set $cursor (i32.add (local.get $cursor) (i32.const 1)))))
    (local.set $magnitude
      (if (result i64) (local.get $negative)
        (then (i64.sub (i64.const 0) (local.get $value)))
        (else (local.get $value))))
    (local.set $digit_count (i32.const 0))
    (block $conversion_done
      (loop $convert
        (br_if $conversion_done (i64.eqz (local.get $magnitude)))
        (i32.store8
          (i32.add (i32.const 8256) (local.get $digit_count))
          (i32.wrap_i64
            (i64.add (i64.rem_u (local.get $magnitude) (i64.const 10)) (i64.const 48))))
        (local.set $magnitude (i64.div_u (local.get $magnitude) (i64.const 10)))
        (local.set $digit_count (i32.add (local.get $digit_count) (i32.const 1)))
        (br $convert)))
    (if (i32.eqz (local.get $digit_count))
      (then
        (i32.store8 (i32.add (i32.const 8256) (i32.const 0)) (i32.const 48))
        (local.set $digit_count (i32.const 1))))
    (block $digits_written
      (loop $write_digits
        (br_if $digits_written (i32.eqz (local.get $digit_count)))
        (local.set $digit_count (i32.sub (local.get $digit_count) (i32.const 1)))
        (i32.store8 (local.get $cursor)
          (i32.load8_u (i32.add (i32.const 8256) (local.get $digit_count))))
        (local.set $cursor (i32.add (local.get $cursor) (i32.const 1)))
        (br $write_digits)))
    (i32.store8 (local.get $cursor) (i32.const 125))
    (local.set $output_length
      (i32.add (i32.sub (local.get $cursor) (i32.const 8192)) (i32.const 1)))
    (i64.or
      (i64.shl (i64.extend_i32_u (local.get $output_length)) (i64.const 32))
      (i64.extend_i32_u (i32.const 8192))))
)