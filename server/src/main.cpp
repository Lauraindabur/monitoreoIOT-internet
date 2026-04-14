#include "server.h"

#include <iostream>
#include <string>

int main(int argc, char* argv[]) {
    if (argc != 3) {
        std::cerr << "Uso: ./server <puerto> <archivoDeLogs>" << std::endl;
        return 1;
    }

    const std::string port = argv[1];
    const std::string logFile = argv[2];

    try {
        return runServer(port, logFile);
    } catch (const std::exception& ex) {
        std::cerr << "Error fatal: " << ex.what() << std::endl;
        return 1;
    }
}
