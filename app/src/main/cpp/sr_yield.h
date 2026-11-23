// GPU time sharing between super-resolution compute and the UI renderer.
//
// One Vulkan submission occupies the whole GPU until it finishes, and HWUI cannot draw a frame meanwhile, so the
// engines call sr_yield() after every submit_and_wait(): a short sleep lets queued frames through, and while the
// reader reports a touch or fling the engine holds between tiles until the pause expires. The pause is a deadline
// rather than a flag, so a reader that never resumes cannot stall background preparation.

#ifndef SR_YIELD_H
#define SR_YIELD_H

#include <atomic>
#include <chrono>
#include <cstdint>
#include <thread>

inline std::atomic<int64_t>& sr_pause_deadline_ms()
{
    static std::atomic<int64_t> deadline{0};
    return deadline;
}

inline int64_t sr_now_ms()
{
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

inline void sr_pause_for(int64_t duration_ms)
{
    const int64_t deadline = sr_now_ms() + duration_ms;
    auto& current = sr_pause_deadline_ms();
    int64_t previous = current.load();
    while (previous < deadline && !current.compare_exchange_weak(previous, deadline))
    {
    }
}

inline void sr_yield()
{
    std::this_thread::sleep_for(std::chrono::milliseconds(2));
    while (sr_now_ms() < sr_pause_deadline_ms().load())
    {
        std::this_thread::sleep_for(std::chrono::milliseconds(8));
    }
}

#endif // SR_YIELD_H
