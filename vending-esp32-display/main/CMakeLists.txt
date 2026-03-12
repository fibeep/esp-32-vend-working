set(srcs "main.c"
        "mdb_handler.c"
        "ble_handler.c"
        "mqtt_handler.c"
        "xor_crypto.c"
        "telemetry.c"
        "led_status.c"
        "nimble.c"
        "webui_server.c"
        "yappy_handler.c"
        "display_handler.c")

idf_component_register(SRCS "${srcs}"
                    INCLUDE_DIRS "."
                    EMBED_TXTFILES "gts_root_r4.pem"
                    EMBED_FILES "../webui/index.html")
