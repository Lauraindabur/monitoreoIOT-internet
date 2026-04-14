#include "logger.h"

#include <chrono>
#include <ctime>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <stdexcept>

Logger::Logger(const std::string& filePath) {
    out_.open(filePath, std::ios::app);
    if (!out_.is_open()) {
        throw std::runtime_error("No se pudo abrir archivo de logs: " + filePath);
    }
}

Logger::~Logger() {
    if (out_.is_open()) {
        out_.close();
    }
}

void Logger::info(const std::string& message) {
    write("INFO", message);
}

void Logger::error(const std::string& message) {
    write("ERROR", message);
}

void Logger::write(const std::string& level, const std::string& message) {
    std::lock_guard<std::mutex> lock(mutex_);
    const std::string line = "[" + timestamp() + "] [" + level + "] " + message;
    std::cout << line << std::endl;
    out_ << line << std::endl;
    out_.flush();
}

std::string Logger::timestamp() const {
    auto now = std::chrono::system_clock::now();
    std::time_t nowTime = std::chrono::system_clock::to_time_t(now);

    std::tm tmValue{};
#ifdef _WIN32
    localtime_s(&tmValue, &nowTime);
#else
    localtime_r(&nowTime, &tmValue);
#endif

    std::ostringstream oss;
    oss << std::put_time(&tmValue, "%Y-%m-%d %H:%M:%S");
    return oss.str();
}
