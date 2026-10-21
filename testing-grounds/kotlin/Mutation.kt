class MutationFixture {
    @Bookkeeping
    var skipped = 0

    fun persist(user: User, event: Event, channel: SendChannel<Int>, items: MutableList<User>) {
        repository.save(user)
        file.writeText("payload")
        bucket.put(user.id, user)
        kafkaTemplate.send(event)
        channel.send(1)
        cache.save(user)
        items.add(user)
        var count = 0
        count++
        user.name = "x"
        handler.persistAll(user)
        handler.updateProfile(user)
        skipped++
    }
}
