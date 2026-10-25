class NeighboursCli {
    fun publish(
        stage: (String) -> String,
        move: (Path, Path) -> Unit = Files::move,
        notify: (Path) -> Unit,
    ) {
        var jsonTemp = Paths.get("neighbours.json.tmp")
        var htmlTemp = Paths.get("neighbours.html.tmp")
        jsonTemp = Paths.get(stage("json"))
        htmlTemp = Paths.get(stage("html"))
        move(jsonTemp, Paths.get("neighbours.json"))
        move(htmlTemp, Paths.get("neighbours.html"))
        notify(Paths.get("neighbours.json"))
    }
}
