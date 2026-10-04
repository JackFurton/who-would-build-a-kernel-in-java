BUILD := build
DUKEC := compiler/build/install/dukec/bin/dukec

# v11.x-binary. Bump deliberately: the Limine protocol changes between majors.
LIMINE_REPO := https://github.com/Limine-Bootloader/limine.git
LIMINE_REV  := 5be26a73d7b7b4d4477d18be94e1d16e615adf56

OVMF ?= $(firstword $(wildcard \
	/opt/homebrew/share/qemu/edk2-x86_64-code.fd \
	/usr/local/share/qemu/edk2-x86_64-code.fd \
	/usr/share/OVMF/OVMF_CODE_4M.fd \
	/usr/share/OVMF/OVMF_CODE.fd \
	/usr/share/qemu/OVMF.fd))
export OVMF

KERNEL_SRCS   := $(shell find kernel/src -name '*.java')
COMPILER_SRCS := $(shell find compiler/src/main -name '*.java') compiler/build.gradle.kts

.PHONY: all compiler run test unit-test boot-test conformance disasm clean

all: $(BUILD)/esp/boot/kernel.elf

compiler: $(DUKEC)

$(DUKEC): $(COMPILER_SRCS)
	./gradlew -q :compiler:installDist
	touch $@

$(BUILD)/kclasses.stamp: $(KERNEL_SRCS)
	rm -rf $(BUILD)/kclasses
	javac --system none --module-source-path java.base=kernel/src -d $(BUILD)/kclasses $(KERNEL_SRCS)
	touch $@

$(BUILD)/kernel.elf: $(DUKEC) $(BUILD)/kclasses.stamp
	$(DUKEC) --classes $(BUILD)/kclasses --entry duke/kernel/Kernel.main --output $@ --map $(BUILD)/kernel.map

$(BUILD)/limine/BOOTX64.EFI:
	rm -rf $(BUILD)/limine
	git init -q $(BUILD)/limine
	git -C $(BUILD)/limine fetch -q --depth=1 $(LIMINE_REPO) $(LIMINE_REV)
	git -C $(BUILD)/limine checkout -q FETCH_HEAD

$(BUILD)/esp/boot/kernel.elf: $(BUILD)/kernel.elf $(BUILD)/limine/BOOTX64.EFI boot/limine.conf
	tools/make-esp.sh $(BUILD)/esp $(BUILD)/limine/BOOTX64.EFI boot/limine.conf $(BUILD)/kernel.elf

run: all
	tools/qemu.sh $(BUILD)/esp -serial mon:stdio

test: unit-test boot-test conformance

unit-test:
	./gradlew -q :compiler:test

boot-test: all
	tools/boot-test.sh $(BUILD)/esp $(BUILD)/serial.log DUKE-BOOT-OK

conformance: $(DUKEC) $(BUILD)/limine/BOOTX64.EFI
	java tools/Conformance.java

disasm: $(BUILD)/kernel.elf
	objdump -d -M intel $(BUILD)/kernel.elf | less

clean:
	rm -rf $(BUILD)
	./gradlew -q clean
