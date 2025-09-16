// mblock_gtest.cc
#include <gtest/gtest.h>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

extern "C" {
#include "mblock.h"
}

// ---------- Helpers (no ASan; use 0xCD fences) ----------
static void fill_cd(void* p, size_t n) { std::memset(p, 0xCD, n); }

static void expect_cd_region(const uint8_t* ptr, size_t n) {
    for (size_t i = 0; i < n; ++i) {
        ASSERT_EQ(ptr[i], 0xCD) << "Fence corrupted at offset " << i;
    }
}

static void expect_cstr_region(const uint8_t* buf, size_t n, const char* payload) {
    const size_t want = std::strlen(payload) + 1;  // include '\0'
    ASSERT_EQ(n, want);
    ASSERT_EQ(std::memcmp(buf, payload, want - 1), 0);
    ASSERT_EQ(buf[want - 1], 0) << "Missing NUL terminator";
}

// -------------------------------------------------------------

TEST(MBlockTest, SizeOfCString_IncludesTerminator) {
    EXPECT_EQ(MBlock_SizeOfCString(""), 1u);
    EXPECT_EQ(MBlock_SizeOfCString("a"), 2u);
    EXPECT_EQ(MBlock_SizeOfCString("hello"), 6u);
}

TEST(MBlockTest, PutCString_WritesExactlyAndAdvances_WhenBufOutProvided) {
    const char* s = "hello";
    const size_t need = MBlock_SizeOfCString(s);

    const size_t L = 16, R = 24;
    std::vector<uint8_t> storage(L + need + R);
    fill_cd(storage.data(), storage.size());

    char* base = reinterpret_cast<char*>(storage.data() + L);

    char* cursor = base;  // non-null bufOut provided: callee must advance it
    MBlock_PutCString(base, s, &cursor);

    expect_cd_region(storage.data(), L);
    expect_cstr_region(storage.data() + L, need, s);
    expect_cd_region(storage.data() + L + need, R);
    ASSERT_EQ(cursor, base + need);
}

TEST(MBlockTest, PutCString_AllowsEmptyString_AdvancesWhenBufOutProvided) {
    const char* s = "";
    const size_t need = MBlock_SizeOfCString(s);

    const size_t L = 8, R = 8;
    std::vector<uint8_t> storage(L + need + R);
    fill_cd(storage.data(), storage.size());

    char* base = reinterpret_cast<char*>(storage.data() + L);

    char* cursor = base;
    MBlock_PutCString(base, s, &cursor);

    expect_cd_region(storage.data(), L);
    expect_cstr_region(storage.data() + L, need, s);
    expect_cd_region(storage.data() + L + need, R);
    ASSERT_EQ(cursor, base + need);
}

TEST(MBlockTest, PutCString_IgnoresNullBufOut_ButStillWrites) {
    // Contract: if bufOut (the third parameter) is nullptr, still write to buf,
    // but do not report the advanced pointer.
    const char* s = "advance";
    const size_t need = MBlock_SizeOfCString(s);

    const size_t L = 8, R = 16;
    std::vector<uint8_t> storage(L + need + R);
    fill_cd(storage.data(), storage.size());

    char* base = reinterpret_cast<char*>(storage.data() + L);

    // Pass nullptr for the third parameter.
    MBlock_PutCString(base, s, /*bufOut=*/nullptr);

    expect_cd_region(storage.data(), L);
    expect_cstr_region(storage.data() + L, need, s);
    expect_cd_region(storage.data() + L + need, R);
}


TEST(MBlockTest, GetCString_RoundTripSingle) {
    const char* s = "roundtrip";
    const size_t need = MBlock_SizeOfCString(s);

    const size_t L = 8, R = 8;
    std::vector<uint8_t> storage(L + need + R);
    fill_cd(storage.data(), storage.size());

    char* base = reinterpret_cast<char*>(storage.data() + L);

    // Write (exercise the advancing path)
    char* cursor = base;
    MBlock_PutCString(base, s, &cursor);
    ASSERT_EQ(cursor, base + need);

    // Parse back
    char* cstrOut = nullptr;
    char* next = base;
    int rc = MBlock_GetCString(base, &cstrOut, &next);
    ASSERT_EQ(rc, 0);
    ASSERT_NE(cstrOut, nullptr);
    ASSERT_EQ(std::string(cstrOut), s);
    ASSERT_EQ(next, base + need);

    expect_cd_region(storage.data() + L + need, R);
    // Ownership of cstrOut unknown; don't free unless specified by API.
}

TEST(MBlockTest, GetCString_TwoConsecutiveStrings) {
    const char* s1 = "hi";
    const char* s2 = "world";
    const size_t n1 = MBlock_SizeOfCString(s1);
    const size_t n2 = MBlock_SizeOfCString(s2);

    const size_t L = 4, R = 12;
    std::vector<uint8_t> storage(L + n1 + n2 + R);
    fill_cd(storage.data(), storage.size());

    char* base = reinterpret_cast<char*>(storage.data() + L);

    // Write #1
    char* p = base;
    MBlock_PutCString(p, s1, &p);
    ASSERT_EQ(p, base + n1);

    // Write #2
    MBlock_PutCString(p, s2, &p);
    ASSERT_EQ(p, base + n1 + n2);

    // Parse #1
    char *out1 = nullptr, *next = base;
    int rc = MBlock_GetCString(base, &out1, &next);
    ASSERT_EQ(rc, 0);
    ASSERT_NE(out1, nullptr);
    EXPECT_EQ(std::string(out1), s1);
    ASSERT_EQ(next, base + n1);

    // Parse #2
    char *out2 = nullptr, *endp = next;
    rc = MBlock_GetCString(next, &out2, &endp);
    ASSERT_EQ(rc, 0);
    ASSERT_NE(out2, nullptr);
    EXPECT_EQ(std::string(out2), s2);
    ASSERT_EQ(endp, base + n1 + n2);

    // Right fence intact
    expect_cd_region(reinterpret_cast<uint8_t*>(endp), R);
}

TEST(MBlockTest, GetCString_EmptyString) {
    const char* s = "";
    const size_t need = MBlock_SizeOfCString(s);

    const size_t L = 4, R = 4;
    std::vector<uint8_t> storage(L + need + R);
    fill_cd(storage.data(), storage.size());

    char* base = reinterpret_cast<char*>(storage.data() + L);

    char* cursor = base;
    MBlock_PutCString(base, s, &cursor);
    ASSERT_EQ(cursor, base + need);

    char* out = nullptr;
    char* next = base;
    int rc = MBlock_GetCString(base, &out, &next);
    ASSERT_EQ(rc, 0);
    ASSERT_NE(out, nullptr);
    EXPECT_EQ(std::string(out), "");
    ASSERT_EQ(next, base + need);

    expect_cd_region(storage.data() + L + need, R);
}
