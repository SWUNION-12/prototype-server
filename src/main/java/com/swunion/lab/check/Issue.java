package com.swunion.lab.check;

public record Issue(IssueType type, String name, String expected, String actual) {
}
