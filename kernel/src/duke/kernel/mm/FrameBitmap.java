package duke.kernel.mm;

/**
 * One bit per 4 KiB page, set when the page is in use. Pure Java with no hardware access, so the
 * allocation logic is testable on its own. Allocation is next-fit: the search resumes where the
 * last one stopped, which keeps it O(1) amortized while memory is mostly free.
 */
public final class FrameBitmap {

    private final long[] used;
    private final long pages;
    private long free;
    private int cursor;

    /** Starts with every page in use; callers free what the memory map says is usable. */
    public FrameBitmap(long pages) {
        this.pages = pages;
        this.used = new long[(int) ((pages + 63) >>> 6)];
        for (int i = 0; i < used.length; i++) {
            used[i] = -1L;
        }
    }

    public long pages() {
        return pages;
    }

    public long freePages() {
        return free;
    }

    public boolean isFree(long page) {
        return (used[(int) (page >>> 6)] & (1L << page)) == 0;
    }

    public void release(long first, long count) {
        for (long page = first; page < first + count; page++) {
            free(page);
        }
    }

    public void free(long page) {
        if (page < 0 || page >= pages) {
            throw new IllegalArgumentException("page " + page + " outside 0.." + pages);
        }
        if (isFree(page)) {
            throw new IllegalStateException("double free of page " + page);
        }
        used[(int) (page >>> 6)] &= ~(1L << page);
        free++;
    }

    /** The lowest free page at or after the cursor, wrapping once; -1 when none is free. */
    public long allocate() {
        for (int scanned = 0; scanned < used.length; scanned++) {
            int word = (cursor + scanned) % used.length;
            long bits = used[word];
            if (bits != -1L) {
                long page = ((long) word << 6) + Long.numberOfTrailingZeros(~bits);
                if (page >= pages) {
                    continue;
                }
                used[word] = bits | (1L << page);
                free--;
                cursor = word;
                return page;
            }
        }
        return -1;
    }
}
