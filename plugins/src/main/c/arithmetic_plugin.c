#include <limits.h>
#include <stddef.h>
#include <stdint.h>

#define MAX_INPUT_BYTES 1048576
#define OUTPUT_CAPACITY 4096
#ifndef PLUGIN_OPERATION
#define PLUGIN_OPERATION 0
#endif

static uint8_t input_buffer[MAX_INPUT_BYTES];
static char output_buffer[OUTPUT_CAPACITY];

__attribute__((export_name("alloc")))
int32_t alloc(int32_t size) {
    if (size < 0 || (uint32_t) size > MAX_INPUT_BYTES) {
        return 0;
    }
    return (int32_t) (uintptr_t) input_buffer;
}

static int parse_factor(const uint8_t *input, size_t length, const char *key, int64_t *result) {
    size_t key_length = 0;
    while (key[key_length] != '\0') {
        key_length++;
    }

    for (size_t i = 0; i + key_length + 2 < length; i++) {
        if (input[i] != '"' || input[i + key_length + 1] != '"') {
            continue;
        }
        size_t matched = 0;
        while (matched < key_length && input[i + matched + 1] == (uint8_t) key[matched]) {
            matched++;
        }
        if (matched != key_length) {
            continue;
        }

        size_t cursor = i + key_length + 2;
        while (cursor < length && (input[cursor] == ' ' || input[cursor] == '\n'
                || input[cursor] == '\r' || input[cursor] == '\t')) {
            cursor++;
        }
        if (cursor >= length || input[cursor++] != ':') {
            continue;
        }
        while (cursor < length && (input[cursor] == ' ' || input[cursor] == '\n'
                || input[cursor] == '\r' || input[cursor] == '\t')) {
            cursor++;
        }

        int negative = cursor < length && input[cursor] == '-';
        if (negative) {
            cursor++;
        }
        size_t digit_start = cursor;
        uint64_t magnitude = 0;
        uint64_t limit = negative ? UINT64_C(9223372036854775808) : INT64_MAX;
        while (cursor < length && input[cursor] >= '0' && input[cursor] <= '9') {
            uint64_t digit = (uint64_t) (input[cursor] - '0');
            if (magnitude > (limit - digit) / 10) {
                return 0;
            }
            magnitude = magnitude * 10 + digit;
            cursor++;
        }
        if (cursor == digit_start) {
            return 0;
        }
        if (cursor < length && input[cursor] != ',' && input[cursor] != '}'
                && input[cursor] != ' ' && input[cursor] != '\n'
                && input[cursor] != '\r' && input[cursor] != '\t') {
            return 0;
        }
        if (negative) {
            *result = magnitude == UINT64_C(9223372036854775808)
                    ? INT64_MIN : -(int64_t) magnitude;
        } else {
            *result = (int64_t) magnitude;
        }
        return 1;
    }
    return 0;
}

static int write_number(char *destination, uint64_t value) {
    char reversed[20];
    int count = 0;
    do {
        reversed[count++] = (char) ('0' + value % 10);
        value /= 10;
    } while (value != 0 && count < (int) sizeof(reversed));

    for (int i = 0; i < count; i++) {
        destination[i] = reversed[count - i - 1];
    }
    return count;
}

static int64_t perform(int operation, int64_t first, int64_t second, int *ok) {
    if (operation == 1) {
        if ((second > 0 && first > INT64_MAX - second)
                || (second < 0 && first < INT64_MIN - second)) {
            *ok = 0;
            return 0;
        }
        return first + second;
    }
    if (operation == 2) {
        if (first > 0 && second < INT64_MIN + first) {
            *ok = 0;
            return 0;
        }
        if (first < 0 && second > INT64_MAX + first) {
            *ok = 0;
            return 0;
        }
        return first - second;
    }
    if (first == 0 || second == 0) {
        return 0;
    }
    if ((first == -1 && second == INT64_MIN)
            || (second == -1 && first == INT64_MIN)) {
        *ok = 0;
        return 0;
    }
    if ((first > 0 && ((second > 0 && first > INT64_MAX / second)
                    || (second < 0 && second < INT64_MIN / first)))
            || (first < 0 && ((second > 0 && first < INT64_MIN / second)
                    || (second < 0 && first < INT64_MAX / second)))) {
        *ok = 0;
        return 0;
    }
    return first * second;
}

static uint64_t pack_result(uint32_t pointer, uint32_t length) {
    return ((uint64_t) length << 32) | pointer;
}

static uint64_t execute(int32_t pointer, int32_t length, int operation) {
    const char *error = "{\"abiVersion\":1,\"error\":\"invalid or overflowing factors\"}";
    int ok = pointer >= 0 && length >= 0
            && (uint32_t) pointer <= MAX_INPUT_BYTES
            && (uint32_t) length <= MAX_INPUT_BYTES - (uint32_t) pointer;
    int64_t first = 0;
    int64_t second = 0;
    if (ok) {
        ok = parse_factor((const uint8_t *) (uintptr_t) pointer, (size_t) length,
                "factor1", &first)
                && parse_factor((const uint8_t *) (uintptr_t) pointer, (size_t) length,
                        "factor2", &second);
    }

    size_t output_length;
    if (!ok) {
        output_length = 0;
        while (error[output_length] != '\0') {
            output_buffer[output_length] = error[output_length];
            output_length++;
        }
    } else {
        int arithmetic_ok = 1;
        int64_t value = perform(operation, first, second, &arithmetic_ok);
        if (!arithmetic_ok) {
            output_length = 0;
            while (error[output_length] != '\0') {
                output_buffer[output_length] = error[output_length];
                output_length++;
            }
        } else {
            const char *prefix = "{\"abiVersion\":1,\"result\":{\"result\":";
            size_t prefix_length = 0;
            while (prefix[prefix_length] != '\0') {
                output_buffer[prefix_length] = prefix[prefix_length];
                prefix_length++;
            }
            output_length = prefix_length;

            uint64_t magnitude;
            if (value < 0) {
                output_buffer[output_length++] = '-';
                magnitude = (uint64_t) (-(value + 1)) + 1;
            } else {
                magnitude = (uint64_t) value;
            }
            output_length += (size_t) write_number(output_buffer + output_length, magnitude);
            output_buffer[output_length++] = '}';
            output_buffer[output_length++] = '}';
        }
    }

    return pack_result((uint32_t) (uintptr_t) output_buffer, (uint32_t) output_length);
}

#if PLUGIN_OPERATION == 0
__attribute__((export_name("multiply")))
uint64_t multiply(int32_t pointer, int32_t length) {
    return execute(pointer, length, 0);
}
#elif PLUGIN_OPERATION == 1
__attribute__((export_name("sum")))
uint64_t sum(int32_t pointer, int32_t length) {
    return execute(pointer, length, 1);
}
#else
__attribute__((export_name("diff")))
uint64_t diff(int32_t pointer, int32_t length) {
    return execute(pointer, length, 2);
}
#endif
