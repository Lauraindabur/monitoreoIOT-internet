#ifndef PROTOCOL_H
#define PROTOCOL_H

#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

enum class ConnectionState {
    CONNECTED_UNREGISTERED,
    REGISTERED_SENSOR,
    REGISTERED_OPERATOR,
    DISCONNECTED
};

struct SensorState {
    std::string id;
    std::string type;
    double lastValue = 0.0;
    std::string lastTimestamp;
    bool hasData = false;
    bool isConnected = false;
    int socketFd = -1;
};

struct OperatorState {
    int socketFd;
    std::string id;
};

struct ClientSession {
    int socketFd;
    std::string ip;
    int port;
    bool isSensor = false;
    bool isOperator = false;
    std::string entityId;
    ConnectionState state = ConnectionState::CONNECTED_UNREGISTERED;
};

struct ServerState {
    std::unordered_map<std::string, SensorState> sensors;
    std::vector<OperatorState> operators;
    std::mutex sensorsMutex;
    std::mutex operatorsMutex;
};

std::string handleProtocolLine(
    const std::string& line,
    ServerState& state,
    ClientSession& client,
    std::vector<std::string>& alertsToBroadcast
);

void removeOperatorBySocket(ServerState& state, int socketFd);
void onClientDisconnected(ServerState& state, const ClientSession& client);

#endif
