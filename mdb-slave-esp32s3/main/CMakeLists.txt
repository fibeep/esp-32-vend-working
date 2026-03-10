set(srcs "mdb-slave-esp32s3.c"
        "webui_server.c"
        "nimble.c")

idf_component_register(SRCS "${srcs}"
                    INCLUDE_DIRS "."
                    EMBED_FILES "../webui/index.html")