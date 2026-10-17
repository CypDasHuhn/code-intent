package testinggrounds

class BookkeepingExample {
    @Bookkeeping
    var skipped = 0

    // scp:bookkeeping
    var rejected = 0

    fun record(value: Int): Boolean {
        if (value < 0) {
            skipped++
            return false
        }
        if (value == 0) {
            rejected += 1
            return false
        }
        return true
    }

    fun total(): Int = skipped + rejected
}
