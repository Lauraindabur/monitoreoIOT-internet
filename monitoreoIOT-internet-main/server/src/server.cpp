#include "server.h"

#include "logger.h"
#include "protocol.h"

#include <arpa/inet.h>
#include <atomic>
#include <cerrno>
#include <csignal>
#include <cstring>
#include <netdb.h>
#include <sys/socket.h>
#include <unistd.h>

#include <iostream>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

namespace {

std::atomic<bool> gStopRequested{false};
int gListenFd = -1;

void handleStopSignal(int) {
    gStopRequested.store(true);
    if (gListenFd != -1) {
        close(gListenFd);
        gListenFd = -1;
    }
}

bool sendLine(int socketFd, const std::string& line) {
    const std::string data = line + "\n";
    size_t totalSent = 0;

    while (totalSent < data.size()) {
        const ssize_t sent = send(socketFd, data.c_str() + totalSent, data.size() - totalSent, 0);
        if (sent <= 0) {
            return false;
        }
        totalSent += static_cast<size_t>(sent);
    }

    return true;
}

bool recvLine(int socketFd, std::string& outLine, bool& timedOut) {
    outLine.clear();
    timedOut = false;
    char ch = '\0';

    while (true) {
        const ssize_t n = recv(socketFd, &ch, 1, 0);
        if (n == 0) {
            return false;
        }
        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                timedOut = true;
            }
            return false;
        }

        if (ch == '\r') {
            continue;
        }
        if (ch == '\n') {
            return true;
        }

        outLine.push_back(ch);
        if (outLine.size() > 4096) {
            return false;
        }
    }
}

void broadcastAlerts(const std::vector<std::string>& alerts, ServerState& state, Logger& logger) {
    if (alerts.empty()) {
        return;
    }

    std::vector<OperatorState> operatorsSnapshot;
    {
        std::lock_guard<std::mutex> lock(state.operatorsMutex);
        operatorsSnapshot = state.operators;
    }

    for (const auto& alert : alerts) {
        std::vector<int> failedSockets;
        for (const auto& op : operatorsSnapshot) {
            if (!sendLine(op.socketFd, alert)) {
                logger.error("Fallo enviando alerta a operador socket=" + std::to_string(op.socketFd));
                failedSockets.push_back(op.socketFd);
            } else {
                logger.info("ALERT enviada a operador=" + op.id + " socket=" + std::to_string(op.socketFd));
            }
        }

        for (int socketFd : failedSockets) {
            removeOperatorBySocket(state, socketFd);
        }
    }
}

void handleClient(int clientFd, sockaddr_storage clientAddr, ServerState& state, Logger& logger) {
    char host[NI_MAXHOST] = {0};
    char service[NI_MAXSERV] = {0};

    const int nameInfo = getnameinfo(
        reinterpret_cast<sockaddr*>(&clientAddr),
        sizeof(clientAddr),
        host,
        sizeof(host),
        service,
        sizeof(service),
        NI_NUMERICHOST | NI_NUMERICSERV
    );

    std::string clientIp = "unknown";
    int clientPort = 0;
    if (nameInfo == 0) {
        clientIp = host;
        clientPort = std::atoi(service);
    }

    ClientSession session{clientFd, clientIp, clientPort, false, false, ""};

    timeval readTimeout{};
    readTimeout.tv_sec = 30;
    readTimeout.tv_usec = 0;
    setsockopt(clientFd, SOL_SOCKET, SO_RCVTIMEO, &readTimeout, sizeof(readTimeout));

    logger.info("Cliente conectado ip=" + clientIp + " puerto=" + std::to_string(clientPort));

    std::string line;
    while (true) {
        bool timedOut = false;
        if (!recvLine(clientFd, line, timedOut)) {
            if (timedOut) {
                logger.info(
                    "Timeout de inactividad ip=" + clientIp +
                    " puerto=" + std::to_string(clientPort)
                );
            }
            break;
        }

        if (line.empty()) {
            continue;
        }

        logger.info(
            "REQ ip=" + clientIp + " puerto=" + std::to_string(clientPort) +
            " msg=\"" + line + "\""
        );

        std::vector<std::string> alerts;
        const std::string response = handleProtocolLine(line, state, session, alerts);

        if (!sendLine(clientFd, response)) {
            logger.error("Error enviando respuesta a ip=" + clientIp + " puerto=" + std::to_string(clientPort));
            break;
        }

        logger.info(
            "RES ip=" + clientIp + " puerto=" + std::to_string(clientPort) +
            " msg=\"" + response + "\""
        );

        broadcastAlerts(alerts, state, logger);
    }

    onClientDisconnected(state, session);
    session.state = ConnectionState::DISCONNECTED;
    close(clientFd);

    std::string role = "anonimo";
    if (session.isSensor) {
        role = "sensor:" + session.entityId;
    } else if (session.isOperator) {
        role = "operador:" + session.entityId;
    }

    logger.info(
        "Cliente desconectado ip=" + clientIp +
        " puerto=" + std::to_string(clientPort) +
        " rol=" + role
    );
}

} // namespace

int runServer(const std::string& port, const std::string& logFile) {
    Logger logger(logFile);
    ServerState state;
    std::vector<std::thread> clientThreads;

    std::signal(SIGINT, handleStopSignal);
    std::signal(SIGTERM, handleStopSignal);

    addrinfo hints{};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_flags = AI_PASSIVE;

    addrinfo* result = nullptr;
    const int gaiCode = getaddrinfo(nullptr, port.c_str(), &hints, &result);
    if (gaiCode != 0) {
        std::cerr << "getaddrinfo fallo: " << gai_strerror(gaiCode) << std::endl;
        return 1;
    }

    int listenFd = -1;
    for (addrinfo* rp = result; rp != nullptr; rp = rp->ai_next) {
        listenFd = socket(rp->ai_family, rp->ai_socktype, rp->ai_protocol);
        if (listenFd == -1) {
            continue;
        }

        int opt = 1;
        setsockopt(listenFd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

        if (bind(listenFd, rp->ai_addr, rp->ai_addrlen) == 0) {
            break;
        }

        close(listenFd);
        listenFd = -1;
    }

    freeaddrinfo(result);

    if (listenFd == -1) {
        std::cerr << "No se pudo abrir/bindear puerto " << port << std::endl;
        return 1;
    }

    if (listen(listenFd, 64) != 0) {
        std::cerr << "listen fallo: " << std::strerror(errno) << std::endl;
        close(listenFd);
        return 1;
    }

    gListenFd = listenFd;

    logger.info("Servidor iniciado en puerto " + port);

    while (!gStopRequested.load()) {
        sockaddr_storage clientAddr{};
        socklen_t clientLen = sizeof(clientAddr);

        const int clientFd = accept(listenFd, reinterpret_cast<sockaddr*>(&clientAddr), &clientLen);
        if (clientFd < 0) {
            if (gStopRequested.load()) {
                break;
            }
            logger.error(std::string("accept fallo: ") + std::strerror(errno));
            continue;
        }

        clientThreads.emplace_back(handleClient, clientFd, clientAddr, std::ref(state), std::ref(logger));
    }

    if (gListenFd != -1) {
        close(gListenFd);
        gListenFd = -1;
    }

    for (auto& t : clientThreads) {
        if (t.joinable()) {
            t.join();
        }
    }

    logger.info("Servidor detenido de forma controlada");
    return 0;
}
