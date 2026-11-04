package com.swunion.lab.docker;

public record ExecResult(int exitCode, String stdout, String stderr) {
}
