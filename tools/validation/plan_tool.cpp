#include <cstring>
#include <iostream>
#include <iterator>
#include <string>
#include "sable/plan.h"
int main(int argc, char** argv) {
  std::string in((std::istreambuf_iterator<char>(std::cin)), std::istreambuf_iterator<char>());
  std::cout << (argc > 1 && !std::strcmp(argv[1], "learn") ? sable::learnUpdateJson(in) : sable::planQueueJson(in));
}
