rootProject.name = "mytetz"

include(
    ":backend:persistence",
    ":backend:llm",
    ":backend:catalog",
    ":backend:graph",
    ":backend:quota",
    ":backend:session",
    ":backend:assess",
    ":backend:api",
)
