# frozen_string_literal: true

require "json"

module MagratheaNormalizedSbom
  class VerificationError < StandardError; end

  module_function

  def npm_purl(name, version)
    unless name.match?(/\A(?:@[A-Za-z0-9._-]+\/)?[A-Za-z0-9._-]+\z/) &&
        version.match?(/\A[0-9A-Za-z][0-9A-Za-z._~-]*\z/)
      raise VerificationError, "Web SBOM npm identity is not safely encodable"
    end

    encoded_name = name.start_with?("@") ? "%40#{name.delete_prefix('@')}" : name
    "pkg:npm/#{encoded_name}@#{version}"
  end

  def verify!(sbom, ledger)
    raise VerificationError, "normalized SBOM is not CycloneDX 1.6" unless
      sbom["bomFormat"] == "CycloneDX" && sbom["specVersion"] == "1.6"

    license_definitions = ledger.fetch("licenses")
    expected_components = ledger.fetch("npmComponents").to_h do |component|
      name = component.fetch("name")
      raise VerificationError, "duplicate Web SBOM component" if
        ledger.fetch("npmComponents").count { |candidate| candidate["name"] == name } != 1

      license = license_definitions.fetch(component.fetch("license")).fetch("spdx")
      [name, component.merge("spdx" => license, "purl" => npm_purl(name, component.fetch("version")))]
    end

    actual_components = {}
    sbom.fetch("components").each do |component|
      role_properties = Array(component["properties"]).select do |property|
        property["name"] == "magrathea:webBundleRole"
      end
      next if role_properties.empty?
      raise VerificationError, "Web SBOM component has ambiguous role metadata" unless role_properties.length == 1

      name = component.fetch("name")
      raise VerificationError, "duplicate Web SBOM component" if actual_components.key?(name)
      actual_components[name] = component
    end
    raise VerificationError, "normalized SBOM Web component inventory mismatch" unless
      actual_components.keys.sort == expected_components.keys.sort

    web_refs = expected_components.map do |name, expected|
      component = actual_components.fetch(name)
      purl = expected.fetch("purl")
      raise VerificationError, "Web SBOM component identity mismatch" unless
        component["type"] == "library" &&
          component["version"] == expected.fetch("version") &&
          component["purl"] == purl &&
          component["bom-ref"] == purl
      raise VerificationError, "Web SBOM component scope mismatch" unless component["scope"] == "required"
      raise VerificationError, "Web SBOM component role mismatch" unless
        Array(component["properties"]).any? do |property|
          property == { "name" => "magrathea:webBundleRole", "value" => expected.fetch("role") }
        end
      license_ids = Array(component["licenses"]).map { |choice| choice.dig("license", "id") }.compact
      raise VerificationError, "Web SBOM component license mismatch" unless license_ids == [expected.fetch("spdx")]
      vcs_references = Array(component["externalReferences"]).select { |reference| reference["type"] == "vcs" }
      raise VerificationError, "Web SBOM component repository mismatch" unless
        vcs_references == [{ "type" => "vcs", "url" => expected.fetch("repository") }]
      purl
    end

    all_refs = [sbom.dig("metadata", "component", "bom-ref")] +
      sbom.fetch("components").map { |component| component["bom-ref"] }
    raise VerificationError, "normalized SBOM contains missing or duplicate bom-ref values" if
      all_refs.any? { |reference| !reference.is_a?(String) || reference.empty? } || all_refs.uniq.length != all_refs.length

    dependencies = Array(sbom["dependencies"])
    dependency_groups = dependencies.group_by { |dependency| dependency["ref"] }
    root_ref = sbom.dig("metadata", "component", "bom-ref")
    root_dependencies = dependency_groups.fetch(root_ref, [])
    raise VerificationError, "normalized SBOM root dependency graph mismatch" unless root_dependencies.length == 1
    root_depends_on = Array(root_dependencies.fetch(0)["dependsOn"])
    raise VerificationError, "normalized SBOM root dependency graph mismatch" unless
      root_depends_on.uniq.length == root_depends_on.length && (web_refs - root_depends_on).empty?

    web_refs.each do |web_ref|
      leaves = dependency_groups.fetch(web_ref, [])
      raise VerificationError, "normalized SBOM Web leaf dependency graph mismatch" unless
        leaves.length == 1 && Array(leaves.fetch(0)["dependsOn"]).empty?
    end
  rescue KeyError, NoMethodError, TypeError => error
    raise VerificationError,
          "normalized SBOM Web contract is incomplete (#{error.class})",
          cause: nil
  end
end

if $PROGRAM_NAME == __FILE__
  begin
    sbom = JSON.parse(File.read(ARGV.fetch(0)))
    ledger = JSON.parse(File.read(ARGV.fetch(1)))
    MagratheaNormalizedSbom.verify!(sbom, ledger)
    puts "MAGRATHEA_NORMALIZED_SBOM_PASS"
  rescue Errno::ENOENT, JSON::ParserError, IndexError, MagratheaNormalizedSbom::VerificationError => error
    warn "normalized SBOM verification failed: #{error.message}"
    exit 1
  end
end
