#ifndef LOGGER_H
#define LOGGER_H

#include <fstream>
#include <mutex>
#include <string>

class Logger {
public:
    explicit Logger(const std::string& filePath);
    ~Logger();

    void info(const std::string& message);
    void error(const std::string& message);

private:
    void write(const std::string& level, const std::string& message);
    std::string timestamp() const;

    std::ofstream out_;
    std::mutex mutex_;
};

#endif
