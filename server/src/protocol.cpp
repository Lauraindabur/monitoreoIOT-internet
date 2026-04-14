#include "protocol.h"

#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <ctime>
#include <iomanip>
#include <regex>
#include <sstream>
#include <unordered_set>

namespace {
std::vector<std::string> splitBySpace(const std::string& line) {
    std::istringstream iss(line);
    std::vector<std::string> tokens;
    std::string token;
    while (iss >> token) {
        tokens.push_back(token);
    }
    return tokens;
}

std::string nowIsoLike() {
    auto now = std::chrono::system_clock::now();
    std::time_t nowTime = std::chrono::system_clock::to_time_t(now);

    std::tm tmValue{};
#ifdef _WIN32
    localtime_s(&tmValue, &nowTime);
#else
    localtime_r(&nowTime, &tmValue);
#endif

    std::ostringstream oss;
    oss << std::put_time(&tmValue, "%Y-%m-%dT%H:%M:%S");
    return oss.str();
}

bool toDouble(const std::string& text, double& outValue) {
    char* end = nullptr;
    outValue = std::strtod(text.c_str(), &end);
    return end != text.c_str() && *end == '\0';
}

bool isValidId(const std::string& id) {
    static const std::regex kIdPattern("^[a-zA-Z0-9_-]{3,32}$");
    return std::regex_match(id, kIdPattern);
}

bool isValidSensorType(const std::string& type) {
    static const std::unordered_set<std::string> kAllowed{
        "temperatura", "humedad", "vibracion", "presion", "consumo"
    };
    return kAllowed.find(type) != kAllowed.end();
}

bool isValueInRange(double value) {
    return value >= -100000.0 && value <= 100000.0;
}

std::string maybeAlert(const SensorState& sensor) {
    if (sensor.type == "temperatura" && sensor.lastValue > 50.0) {
        return "ALERT " + sensor.id + " temperatura_alta " + std::to_string(sensor.lastValue) + " " + sensor.lastTimestamp;
    }
    if (sensor.type == "humedad" && sensor.lastValue < 20.0) {
        return "ALERT " + sensor.id + " humedad_baja " + std::to_string(sensor.lastValue) + " " + sensor.lastTimestamp;
    }
    if (sensor.type == "vibracion" && sensor.lastValue > 80.0) {
        return "ALERT " + sensor.id + " vibracion_alta " + std::to_string(sensor.lastValue) + " " + sensor.lastTimestamp;
    }
    if (sensor.type == "presion" && (sensor.lastValue < 900.0 || sensor.lastValue > 1100.0)) {
        return "ALERT " + sensor.id + " presion_anomala " + std::to_string(sensor.lastValue) + " " + sensor.lastTimestamp;
    }
    if (sensor.type == "consumo" && sensor.lastValue > 1000.0) {
        return "ALERT " + sensor.id + " consumo_alto " + std::to_string(sensor.lastValue) + " " + sensor.lastTimestamp;
    }
    return "";
}
} // namespace

std::string handleProtocolLine(
    const std::string& line,
    ServerState& state,
    ClientSession& client,
    std::vector<std::string>& alertsToBroadcast
) {
    const std::vector<std::string> t = splitBySpace(line);
    if (t.empty()) {
        return "ERROR 400 BAD_REQUEST";
    }

    if (t[0] == "PING") {
        if (t.size() != 1) {
            return "ERROR 400 BAD_REQUEST";
        }
        return "OK PONG";
    }

    if (t[0] == "REGISTER") {
        if (t.size() < 3) {
            return "ERROR 400 BAD_REQUEST";
        }

        if (client.isSensor || client.isOperator) {
            return "ERROR 409 ALREADY_REGISTERED";
        }

        if (t[1] == "SENSOR") {
            if (t.size() != 4) {
                return "ERROR 400 BAD_REQUEST";
            }

            const std::string sensorId = t[2];
            const std::string sensorType = t[3];

            if (!isValidId(sensorId)) {
                return "ERROR 400 INVALID_ID";
            }
            if (!isValidSensorType(sensorType)) {
                return "ERROR 422 INVALID_SENSOR_TYPE";
            }

            {
                std::lock_guard<std::mutex> lock(state.sensorsMutex);
                auto it = state.sensors.find(sensorId);
                if (it != state.sensors.end()) {
                    if (it->second.type != sensorType) {
                        return "ERROR 409 SENSOR_TYPE_MISMATCH";
                    }
                    if (it->second.isConnected) {
                        return "ERROR 409 ALREADY_EXISTS";
                    }
                    it->second.isConnected = true;
                    it->second.socketFd = client.socketFd;
                } else {
                    SensorState sensor;
                    sensor.id = sensorId;
                    sensor.type = sensorType;
                    sensor.isConnected = true;
                    sensor.socketFd = client.socketFd;
                    state.sensors[sensorId] = sensor;
                }
            }

            client.isSensor = true;
            client.entityId = sensorId;
            client.state = ConnectionState::REGISTERED_SENSOR;
            return "OK REGISTERED SENSOR " + sensorId;
        }

        if (t[1] == "OPERATOR") {
            if (t.size() != 3) {
                return "ERROR 400 BAD_REQUEST";
            }

            const std::string operatorId = t[2];
            if (!isValidId(operatorId)) {
                return "ERROR 400 INVALID_ID";
            }

            {
                std::lock_guard<std::mutex> lock(state.operatorsMutex);
                for (const auto& op : state.operators) {
                    if (op.id == operatorId) {
                        return "ERROR 409 ALREADY_EXISTS";
                    }
                }
                state.operators.push_back(OperatorState{client.socketFd, operatorId});
            }
            client.isOperator = true;
            client.entityId = operatorId;
            client.state = ConnectionState::REGISTERED_OPERATOR;
            return "OK REGISTERED OPERATOR " + operatorId;
        }

        return "ERROR 400 BAD_REQUEST";
    }

    if (t[0] == "DATA") {
        if (t.size() != 3) {
            return "ERROR 400 BAD_REQUEST";
        }

        if (!client.isSensor) {
            return "ERROR 403 NOT_REGISTERED";
        }

        const std::string sensorId = t[1];
        if (!isValidId(sensorId)) {
            return "ERROR 400 INVALID_ID";
        }
        if (client.entityId != sensorId) {
            return "ERROR 403 FORBIDDEN_SENSOR_ID";
        }

        double value = 0.0;
        if (!toDouble(t[2], value)) {
            return "ERROR 400 BAD_VALUE";
        }
        if (!isValueInRange(value)) {
            return "ERROR 400 BAD_VALUE";
        }

        SensorState updated;
        {
            std::lock_guard<std::mutex> lock(state.sensorsMutex);
            auto it = state.sensors.find(sensorId);
            if (it == state.sensors.end()) {
                return "ERROR 404 SENSOR_NOT_FOUND";
            }
            it->second.lastValue = value;
            it->second.lastTimestamp = nowIsoLike();
            it->second.hasData = true;
            updated = it->second;
        }

        const std::string alert = maybeAlert(updated);
        if (!alert.empty()) {
            alertsToBroadcast.push_back(alert);
        }

        return "OK DATA_RECEIVED " + sensorId;
    }

    if (t[0] == "GET_SENSORS") {
        if (t.size() != 1) {
            return "ERROR 400 BAD_REQUEST";
        }
        if (!client.isSensor && !client.isOperator) {
            return "ERROR 403 NOT_REGISTERED";
        }

        std::lock_guard<std::mutex> lock(state.sensorsMutex);
        std::ostringstream oss;
        oss << "SENSORS " << state.sensors.size();
        for (const auto& kv : state.sensors) {
            oss << " " << kv.second.id << ":" << kv.second.type;
        }
        return oss.str();
    }

    if (t[0] == "GET_LAST") {
        if (t.size() != 2) {
            return "ERROR 400 BAD_REQUEST";
        }
        if (!client.isSensor && !client.isOperator) {
            return "ERROR 403 NOT_REGISTERED";
        }
        if (!isValidId(t[1])) {
            return "ERROR 400 INVALID_ID";
        }

        std::lock_guard<std::mutex> lock(state.sensorsMutex);
        auto it = state.sensors.find(t[1]);
        if (it == state.sensors.end()) {
            return "ERROR 404 SENSOR_NOT_FOUND";
        }
        if (!it->second.hasData) {
            return "ERROR 404 NO_DATA";
        }

        std::ostringstream oss;
        oss << "LAST " << it->second.id << " " << it->second.lastValue << " " << it->second.lastTimestamp;
        return oss.str();
    }

    return "ERROR 400 UNKNOWN_COMMAND";
}

void removeOperatorBySocket(ServerState& state, int socketFd) {
    std::lock_guard<std::mutex> lock(state.operatorsMutex);
    state.operators.erase(
        std::remove_if(
            state.operators.begin(),
            state.operators.end(),
            [socketFd](const OperatorState& op) { return op.socketFd == socketFd; }
        ),
        state.operators.end()
    );
}

void onClientDisconnected(ServerState& state, const ClientSession& client) {
    if (client.isOperator) {
        removeOperatorBySocket(state, client.socketFd);
    }

    if (client.isSensor) {
        std::lock_guard<std::mutex> lock(state.sensorsMutex);
        auto it = state.sensors.find(client.entityId);
        if (it != state.sensors.end() && it->second.socketFd == client.socketFd) {
            it->second.isConnected = false;
            it->second.socketFd = -1;
        }
    }
}
