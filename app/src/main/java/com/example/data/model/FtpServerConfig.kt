package com.example.data.model

enum class ServerProtocol {
    FTP,
    HTTP_WEB
}

data class FtpServerConfig(
    val name: String = "Samonline FTP",
    val host: String = "172.16.50.4",
    val port: Int = 21,
    val username: String = "anonymous",
    val password: String = "",
    val isPassiveMode: Boolean = true,
    val protocol: ServerProtocol = ServerProtocol.FTP,
    val initialPath: String = "/"
) {
    companion object {
        val SAMONLINE_FTP = FtpServerConfig(
            name = "Samonline FTP",
            host = "172.16.50.4",
            port = 21,
            username = "anonymous",
            password = "",
            isPassiveMode = true,
            protocol = ServerProtocol.FTP,
            initialPath = "/"
        )

        val SAMONLINE_WEB = FtpServerConfig(
            name = "Samonline Web Media",
            host = "http://172.16.50.4",
            port = 80,
            username = "",
            password = "",
            isPassiveMode = false,
            protocol = ServerProtocol.HTTP_WEB,
            initialPath = "/"
        )

        val CUSTOM_FTP = FtpServerConfig(
            name = "Custom FTP Server",
            host = "ftp.samonline.net",
            port = 21,
            username = "anonymous",
            password = "",
            isPassiveMode = true,
            protocol = ServerProtocol.FTP,
            initialPath = "/"
        )
    }
}
