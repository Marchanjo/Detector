/* Copyright 2016 The TensorFlow Authors. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================*/

#include "tensorflow/examples/Detector/jni/tensorflow/object_tracking/time_log.h"

#ifdef LOG_TIME
// Storage for logging functionality.
int num_time_logs = 0;
LogEntry time_logs[NUM_LOGS];

int num_avg_entries = 0;
AverageEntry avg_entries[NUM_LOGS];
#endif
