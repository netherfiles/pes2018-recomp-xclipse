// See texture_store.h.
#include "texture_store.h"
#include "../texture.h"
#include "../../env.h"
#include "../../log.h"

#include <atomic>
#include <chrono>
#include <algorithm>
#include <condition_variable>
#include <cstddef>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#ifdef _WIN32
// A desktop GPU samples BC itself, so nothing is converted and nothing is kept.
bool vk::texstore::Open() { return false; }
bool vk::texstore::Load(const gpu::TextureData&, uint32_t, gpu::TextureData&, int&, uint64_t& hash)
{
    hash = 0;
    return false;
}
void vk::texstore::Save(const gpu::TextureData&, uint32_t, uint64_t, const gpu::TextureData&, int) {}
vk::texstore::Stats vk::texstore::Counts() { return {}; }
#else

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace
{
    // The file: a header, then records one after another. A record is its head,
    // its levels and its bytes. Bump kEncoder whenever the conversion's output
    // changes (bc_decoder.cpp), so that no session samples an older encoder's blocks.
    constexpr char kMagic[8] = { 'P', 'E', 'S', '1', '7', 'T', 'X', 'S' };
    constexpr uint32_t kVersion = 1, kEncoder = 1;
    constexpr uint32_t kRecordMagic = 0x31525854;   // "TXR1"

    struct FileHeader { char magic[8]; uint32_t version, encoder; };

    // What a record answers for: the source blocks' hash and everything about
    // their shape that the conversion reads.
    struct Key
    {
        uint64_t hash = 0;
        uint32_t xenosFormat = 0, sourceBytes = 0, width = 0, height = 0, layers = 0, levels = 0;
        uint32_t blocksWide = 0, blocksHigh = 0, bytesPerBlock = 0, volume = 0;
        bool operator==(const Key& o) const { return std::memcmp(this, &o, sizeof *this) == 0; }
    };

    struct RecordHead
    {
        uint32_t magic;
        uint32_t levelCount;
        Key key;
        int32_t vkFormat;
        uint32_t bytesPerBlock;
        uint64_t payloadBytes;
        uint64_t check;   // of everything above, so a torn head is not trusted
    };

    struct StoredLevel
    {
        uint32_t width, height, depth, blocksWide, blocksHigh, layers;
        uint64_t offset, size;
    };

    struct Entry
    {
        Key key;
        int32_t vkFormat;
        uint32_t bytesPerBlock;
        uint64_t payloadAt, payloadBytes;
        std::vector<StoredLevel> levels;
    };

    // One record waiting for the writer: where it belongs in the index, and the
    // payload on its own -- the head is made from the entry when the write
    // happens, so the payload is the only thing that has to be held.
    struct Job
    {
        Entry entry;
        std::vector<uint8_t> payload;
    };

    // Four lanes, as the texture cache signs guest memory (texture_cache.cpp).
    uint64_t Hash(const uint8_t* base, size_t bytes)
    {
        constexpr uint64_t kPrime = 0x100000001B3ull;
        uint64_t lane[4] = { 0xCBF29CE484222325ull, 0x84222325CBF29CE4ull,
                             0x9E3779B97F4A7C15ull, 0x7F4A7C159E3779B9ull };
        size_t at = 0;
        for (; at + 32 <= bytes; at += 32)
        {
            uint64_t w[4];
            std::memcpy(w, base + at, sizeof w);
            for (int i = 0; i < 4; i++) lane[i] = (lane[i] ^ w[i]) * kPrime;
        }
        for (; at < bytes; at++) lane[at & 3] = (lane[at & 3] ^ base[at]) * kPrime;
        uint64_t hash = uint64_t(bytes) * 0x9E3779B97F4A7C15ull;
        for (int i = 0; i < 4; i++) hash = (hash ^ lane[i]) * kPrime ^ (lane[i] >> 29);
        return hash;
    }

    uint64_t CheckOf(const RecordHead& head)
    {
        return Hash(reinterpret_cast<const uint8_t*>(&head), offsetof(RecordHead, check));
    }

    Key KeyOf(const gpu::TextureData& source, uint32_t xenosFormat, uint64_t hash)
    {
        Key key;
        key.hash = hash;
        key.xenosFormat = xenosFormat;
        key.sourceBytes = uint32_t(source.bytes.size());
        key.width = source.width;
        key.height = source.height;
        key.layers = source.layers;
        key.levels = uint32_t(source.levels.size());
        key.blocksWide = source.blocksWide;
        key.blocksHigh = source.blocksHigh;
        key.bytesPerBlock = source.bytesPerBlock;
        key.volume = source.volume;
        return key;
    }

    struct State
    {
        std::once_flag opened;
        std::atomic<bool> ready{false};   // open, scanned: Load and Save may use it
        int fd = -1;
        uint64_t end = 0;           // where the next record goes
        uint64_t limit = 0;         // the file stops growing here
        std::mutex lock;            // index, end
        std::unordered_map<uint64_t, Entry> index;   // by key.hash

        // The writer's queue: one payload each, ready to append.
        std::mutex queueLock;
        std::condition_variable wake;
        std::deque<Job> queue;
        // Payload buffers the writer has finished with, kept for the next Save:
        // a texture's payload is four to sixteen megabytes, and allocating and
        // freeing one per texture cost more than the copy into it. Capped, since
        // what is not reused is only memory held for a texture that may never
        // come.
        std::vector<std::vector<uint8_t>> spare;
        uint64_t queuedBytes = 0;
        std::thread writer;

        std::atomic<uint64_t> hits{0}, misses{0}, saved{0}, savedBytes{0}, hashNs{0}, readNs{0}, dropped{0};
    };
    State& S()
    {
        static State* s = new State;   // never destroyed: the writer may outlive exit's destructors
        return *s;
    }

    // At most this much waiting to be written; past it a conversion is not kept.
    constexpr uint64_t kQueueMost = 64ull << 20;

    // Payload buffers the writer keeps for the next Save (State::spare).
    constexpr size_t kSpareMost = 4;

    // How much of the file is read ahead when it is opened. See ReadAhead.
    constexpr uint64_t kReadAheadMost = 8ull << 20;

    // The size the store grows to unless PES17_TEXTURE_STORE_MB says otherwise.
    constexpr uint64_t kDefaultStoreMB = 1024;

    bool ReadAll(int fd, void* into, size_t bytes, uint64_t at)
    {
        uint8_t* to = static_cast<uint8_t*>(into);
        while (bytes)
        {
            const ssize_t n = pread(fd, to, bytes, off_t(at));
            if (n <= 0) return false;
            to += n; at += uint64_t(n); bytes -= size_t(n);
        }
        return true;
    }

    bool WriteAll(int fd, const void* from, size_t bytes, uint64_t at)
    {
        const uint8_t* p = static_cast<const uint8_t*>(from);
        while (bytes)
        {
            const ssize_t n = pwrite(fd, p, bytes, off_t(at));
            if (n <= 0) return false;
            p += n; at += uint64_t(n); bytes -= size_t(n);
        }
        return true;
    }

    // Reads the records back into the index; the file ends after the last whole one.
    void Scan(State& s, uint64_t size)
    {
        uint64_t at = sizeof(FileHeader);
        while (at + sizeof(RecordHead) <= size)
        {
            RecordHead head;
            if (!ReadAll(s.fd, &head, sizeof head, at) || head.magic != kRecordMagic ||
                head.check != CheckOf(head) || head.levelCount > 32)
                break;
            Entry entry;
            entry.key = head.key;
            entry.vkFormat = head.vkFormat;
            entry.bytesPerBlock = head.bytesPerBlock;
            entry.levels.resize(head.levelCount);
            const uint64_t levelsAt = at + sizeof head;
            const uint64_t levelBytes = uint64_t(head.levelCount) * sizeof(StoredLevel);
            entry.payloadAt = levelsAt + levelBytes;
            entry.payloadBytes = head.payloadBytes;
            if (entry.payloadAt + entry.payloadBytes > size ||
                (levelBytes && !ReadAll(s.fd, entry.levels.data(), size_t(levelBytes), levelsAt)))
                break;
            at = entry.payloadAt + entry.payloadBytes;
            s.index[entry.key.hash] = std::move(entry);
        }
        s.end = at;
        if (at < size && ftruncate(s.fd, off_t(at)) == 0)
            LOGW("texstore: dropped %llu bytes after the last whole record", (unsigned long long)(size - at));
    }

    // A bounded read ahead: the head of the file, where the oldest records are,
    // into the kernel's cache before the first texture asks for one. The whole
    // file is deliberately left alone -- at the default size it is a gigabyte,
    // and reading it all cost seconds before the first frame for records the
    // session may never name. A record past this prefix is read when it is
    // asked for, about 0.6 ms a texture once, which is the trade for a launch
    // that does not wait on the disk.
    void ReadAhead(State& s, uint64_t size)
    {
        const uint64_t bytes = std::min(size, kReadAheadMost);
        if (!bytes) return;
        const auto started = std::chrono::steady_clock::now();
        std::vector<uint8_t> chunk(static_cast<size_t>(bytes));
        if (!ReadAll(s.fd, chunk.data(), chunk.size(), 0)) return;
        LOGI("texstore: read ahead %llu MB in %.1f ms of a %llu MB file",
             (unsigned long long)(bytes >> 20),
             std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count(),
             (unsigned long long)(size >> 20));
    }

    // A payload buffer back for the next Save. Called on the writer thread, once
    // the write that used it is done.
    void Recycle(State& s, std::vector<uint8_t>&& payload)
    {
        std::lock_guard<std::mutex> hold(s.queueLock);
        if (s.spare.size() >= kSpareMost) return;
        payload.clear();
        s.spare.push_back(std::move(payload));
    }

    void WriterLoop()
    {
        State& s = S();
        for (;;)
        {
            Job job;
            {
                std::unique_lock<std::mutex> hold(s.queueLock);
                s.wake.wait(hold, [&] { return !s.queue.empty(); });
                job = std::move(s.queue.front());
                s.queue.pop_front();
                s.queuedBytes -= job.payload.size() + sizeof(RecordHead) +
                                 job.entry.levels.size() * sizeof(StoredLevel);
            }
            Entry& entry = job.entry;
            // The head is made here rather than carried in the queue beside the
            // payload: it is a hundred bytes of fields the entry already holds.
            RecordHead head{};
            head.magic = kRecordMagic;
            head.levelCount = uint32_t(entry.levels.size());
            head.key = entry.key;
            head.vkFormat = entry.vkFormat;
            head.bytesPerBlock = entry.bytesPerBlock;
            head.payloadBytes = entry.payloadBytes;
            head.check = CheckOf(head);
            const size_t levelBytes = entry.levels.size() * sizeof(StoredLevel);
            const uint64_t total = sizeof head + levelBytes + entry.payloadBytes;
            uint64_t at = 0;
            bool reserved = false;
            {
                std::lock_guard<std::mutex> hold(s.lock);
                if (!s.index.count(entry.key.hash) && s.end + total <= s.limit)
                {
                    at = s.end;
                    s.end += total;
                    reserved = true;
                }
            }
            // Already kept, or the file is full: nothing to write.
            if (!reserved) { Recycle(s, std::move(job.payload)); continue; }
            // The head goes last, so a record is only found once all of it is there.
            if (!WriteAll(s.fd, job.payload.data(), job.payload.size(),
                          at + sizeof head + levelBytes) ||
                (levelBytes && !WriteAll(s.fd, entry.levels.data(), levelBytes, at + sizeof head)) ||
                !WriteAll(s.fd, &head, sizeof head, at))
            {
                LOGW("texstore: a write failed; the store stops growing");
                {
                    std::lock_guard<std::mutex> hold(s.lock);
                    s.limit = 0;
                }
                Recycle(s, std::move(job.payload));
                continue;
            }
            entry.payloadAt = at + sizeof head + levelBytes;
            {
                std::lock_guard<std::mutex> hold(s.lock);
                s.index[entry.key.hash] = std::move(entry);
                s.saved++;
                s.savedBytes += total;
            }
            Recycle(s, std::move(job.payload));
        }
    }

    void OpenOnce(State& s)
    {
        const char* path = env::Text("PES17_TEXTURE_STORE");
        if (!path || !*path) return;
        s.limit = env::Number("PES17_TEXTURE_STORE_MB", kDefaultStoreMB) << 20;
        s.fd = open(path, O_RDWR | O_CREAT | O_CLOEXEC, 0644);
        if (s.fd < 0) { LOGW("texstore: cannot open %s", path); return; }
        struct stat st{};
        fstat(s.fd, &st);
        FileHeader header{};
        const bool fresh = uint64_t(st.st_size) < sizeof header ||
                           !ReadAll(s.fd, &header, sizeof header, 0) ||
                           std::memcmp(header.magic, kMagic, sizeof kMagic) != 0 ||
                           header.version != kVersion || header.encoder != kEncoder;
        const auto started = std::chrono::steady_clock::now();
        if (fresh)
        {
            if (st.st_size) LOGW("texstore: %s is from another version; starting it again", path);
            std::memcpy(header.magic, kMagic, sizeof kMagic);
            header.version = kVersion;
            header.encoder = kEncoder;
            if (ftruncate(s.fd, 0) != 0 || !WriteAll(s.fd, &header, sizeof header, 0))
            {
                LOGW("texstore: cannot write %s", path);
                close(s.fd);
                s.fd = -1;
                return;
            }
            s.end = sizeof header;
        }
        else
        {
            Scan(s, uint64_t(st.st_size));
        }
        LOGI("texstore: %s: %zu textures, %llu MB, opened in %.1f ms", path, s.index.size(),
             (unsigned long long)(s.end >> 20),
             std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count());
        s.ready.store(true, std::memory_order_release);
        ReadAhead(s, s.end);
        WriterLoop();
    }
}

// The file is opened, scanned and read ahead on a thread of its own, which then
// stays on as the writer: the caller -- the thread that parses the command
// stream -- never waits for any of it. Until the scan is done the store answers
// that it has nothing.
bool vk::texstore::Open()
{
    State& s = S();
    std::call_once(s.opened, [&] { s.writer = std::thread([&s] { OpenOnce(s); }); s.writer.detach(); });
    return s.ready.load(std::memory_order_acquire);
}

bool vk::texstore::Load(const gpu::TextureData& source, uint32_t xenosFormat, gpu::TextureData& out,
                        int& vkFormat, uint64_t& hash)
{
    hash = 0;
    if (!Open()) return false;
    State& s = S();
    auto started = std::chrono::steady_clock::now();
    const auto lap = [&](std::atomic<uint64_t>& into) {
        const auto now = std::chrono::steady_clock::now();
        into += uint64_t(std::chrono::duration_cast<std::chrono::nanoseconds>(now - started).count());
        started = now;
    };
    hash = Hash(source.bytes.data(), source.bytes.size());
    const Key key = KeyOf(source, xenosFormat, hash);
    lap(s.hashNs);
    uint64_t payloadAt = 0, payloadBytes = 0;
    {
        std::lock_guard<std::mutex> hold(s.lock);
        const auto found = s.index.find(key.hash);
        if (found == s.index.end() || !(found->second.key == key))
        {
            s.misses++;
            return false;
        }
        const Entry& entry = found->second;
        vkFormat = entry.vkFormat;
        out.levels.resize(entry.levels.size());
        for (size_t i = 0; i < entry.levels.size(); i++)
        {
            const StoredLevel& from = entry.levels[i];
            gpu::TextureData::Level& to = out.levels[i];
            to.width = from.width; to.height = from.height; to.depth = from.depth;
            to.blocksWide = from.blocksWide; to.blocksHigh = from.blocksHigh; to.layers = from.layers;
            to.offset = size_t(from.offset); to.size = size_t(from.size);
        }
        out.bytesPerBlock = entry.bytesPerBlock;
        payloadAt = entry.payloadAt;
        payloadBytes = entry.payloadBytes;
    }
    out.bytes.resize(size_t(payloadBytes));
    if (!ReadAll(s.fd, out.bytes.data(), size_t(payloadBytes), payloadAt))
    {
        s.misses++;
        return false;
    }
    out.ok = true;
    out.error = nullptr;
    out.width = source.width;
    out.height = source.height;
    out.blocksWide = source.blocksWide;
    out.blocksHigh = source.blocksHigh;
    out.layers = source.layers;
    out.volume = source.volume;
    out.expanded = false;
    out.minLevel = source.minLevel;
    s.hits++;
    lap(s.readNs);
    return true;
}

void vk::texstore::Save(const gpu::TextureData& source, uint32_t xenosFormat, uint64_t hash,
                        const gpu::TextureData& converted, int vkFormat)
{
    if (!Open() || !converted.ok) return;
    State& s = S();
    Entry entry;
    entry.key = KeyOf(source, xenosFormat, hash);
    entry.vkFormat = vkFormat;
    entry.bytesPerBlock = converted.bytesPerBlock;
    entry.payloadBytes = converted.bytes.size();
    entry.levels.reserve(converted.levels.size());
    for (const gpu::TextureData::Level& level : converted.levels)
        entry.levels.push_back({ level.width, level.height, level.depth, level.blocksWide,
                                 level.blocksHigh, level.layers, level.offset, level.size });
    if (entry.levels.size() > 32) return;

    const size_t levelBytes = entry.levels.size() * sizeof(StoredLevel);
    const size_t total = sizeof(RecordHead) + levelBytes + size_t(entry.payloadBytes);

    // One copy of the payload, into a buffer the writer has already finished
    // with. The writer cannot read `converted` itself: it lives in the decoder's
    // scratch, which the next texture overwrites, so a copy is due either way --
    // but an allocation of the whole image per texture, on top of it, was most
    // of what a Save cost. The header and levels are not copied at all; the
    // writer makes the head from the entry.
    std::vector<uint8_t> payload;
    {
        std::lock_guard<std::mutex> hold(s.queueLock);
        if (!s.spare.empty())
        {
            payload = std::move(s.spare.back());
            s.spare.pop_back();
        }
    }
    payload.assign(converted.bytes.begin(), converted.bytes.end());
    {
        std::lock_guard<std::mutex> hold(s.queueLock);
        if (s.queuedBytes + total > kQueueMost)
        {
            s.dropped++;
            if (s.spare.size() < kSpareMost) { payload.clear(); s.spare.push_back(std::move(payload)); }
            return;
        }
        s.queuedBytes += total;
        s.queue.push_back(Job{ std::move(entry), std::move(payload) });
    }
    s.wake.notify_one();
}

vk::texstore::Stats vk::texstore::Counts()
{
    State& s = S();
    Stats stats{ s.hits, s.misses, s.saved, s.savedBytes, s.hashNs, s.readNs, 0, 0, s.dropped };
    std::lock_guard<std::mutex> hold(s.lock);
    stats.records = s.index.size();
    stats.fileBytes = s.end;
    return stats;
}

#endif  // _WIN32
